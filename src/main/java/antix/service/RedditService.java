package antix.service;

import antix.model.SocialMediaPost;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Recherche Reddit via l'API officielle OAuth avec repli vers l'API publique JSON.
 * Variables attendues (env/propriétés Spring) :
 *   reddit.client-id  / ${REDDIT_CLIENT_ID}
 *   reddit.client-secret / ${REDDIT_CLIENT_SECRET}
 *   reddit.user-agent / ${REDDIT_USER_AGENT}
 */
@Service
public class RedditService implements SocialMediaService {

    private static final String OAUTH_TOKEN_URL = "https://www.reddit.com/api/v1/access_token";
    private static final String OAUTH_SEARCH_URL = "https://oauth.reddit.com/search";
    private static final String PUBLIC_SEARCH_URL = "https://www.reddit.com/search.json";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String clientId;
    private final String clientSecret;
    private final String userAgent;

    public RedditService(
            @Value("${reddit.client-id:${REDDIT_CLIENT_ID:}}") String clientId,
            @Value("${reddit.client-secret:${REDDIT_CLIENT_SECRET:}}") String clientSecret,
            @Value("${reddit.user-agent:${REDDIT_USER_AGENT:AntixBot/1.0 (+https://example.com)}}") String userAgent
    ) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.userAgent = (userAgent == null || userAgent.isBlank())
                ? "AntixBot/1.0 (+https://example.com)"
                : userAgent.trim();
    }

    // ========= Implémentation SocialMediaService =========

    @Override
    public List<SocialMediaPost> fetchPostsFromTag(String tag, int limit) {
        if (tag == null || tag.isBlank()) return List.of();
        int capped = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));

        // 1) Essayer OAuth (plus fiable en prod cloud)
        if (hasCredentials()) {
            try {
                String token = getAppOnlyToken()
                        .orElseThrow(() -> new IllegalStateException("Reddit OAuth: access_token manquant"));
                List<SocialMediaPost> posts = searchWithOAuth(tag, capped, token);
                if (!posts.isEmpty()) return posts;
            } catch (Exception e) {
                System.err.println("[RedditService] OAuth search failed, fallback to public JSON: " + e.getMessage());
            }
        }

        // 2) Repli API publique JSON
        try {
            return searchPublicJson(tag, Math.min(capped, 50));
        } catch (Exception e) {
            System.err.println("[RedditService] Public JSON search failed: " + e.getMessage());
            return List.of();
        }
    }

    @Override
    public String getPlatformName() {
        return "reddit";
    }

    // ======================= OAuth =======================

    private boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    private Optional<String> getAppOnlyToken() throws IOException, InterruptedException {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        String form = "grant_type=client_credentials";
        HttpRequest req = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            JsonNode json = mapper.readTree(resp.body());
            String token = json.path("access_token").asText(null);
            return Optional.ofNullable(token);
        } else {
            System.err.println("[RedditService] Token request failed: " + resp.statusCode() + " body=" + resp.body());
            return Optional.empty();
        }
    }

    private List<SocialMediaPost> searchWithOAuth(String query, int limit, String bearerToken)
            throws IOException, InterruptedException {

        String url = OAUTH_SEARCH_URL
                + "?q=" + url(query)
                + "&limit=" + limit
                + "&sort=relevance&type=link&restrict_sr=false";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new IllegalStateException("Unauthorized/Forbidden Reddit API (vérifie client/secret/scopes)");
        }
        if (resp.statusCode() == 429) {
            throw new IllegalStateException("Rate limited (429) par Reddit API");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Reddit OAuth search failed: " + resp.statusCode());
        }
        return parseRedditListing(resp.body());
    }

    // ==================== Public JSON fallback ====================

    private List<SocialMediaPost> searchPublicJson(String query, int limit)
            throws IOException, InterruptedException {

        String url = PUBLIC_SEARCH_URL
                + "?q=" + url(query)
                + "&limit=" + limit
                + "&sort=relevance&type=link&restrict_sr=false";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.USER_AGENT, userAgent)
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            throw new IllegalStateException("Rate limited (429) par reddit.com public JSON");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Public JSON search failed: " + resp.statusCode());
        }
        return parseRedditListing(resp.body());
    }

    // ========================= Parsing =========================

    private List<SocialMediaPost> parseRedditListing(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode children = root.path("data").path("children");
        List<SocialMediaPost> out = new ArrayList<>();

        if (children.isArray()) {
            for (JsonNode child : children) {
                JsonNode d = child.path("data");
                String id = text(d, "id");
                String title = text(d, "title");
                String selftext = text(d, "selftext");
                String author = text(d, "author");
                String subreddit = text(d, "subreddit");
                String permalink = "https://www.reddit.com" + text(d, "permalink");
                String url = text(d, "url");
                long createdUtc = d.path("created_utc").asLong(0);
                int score = d.path("score").asInt(0);
                int comments = d.path("num_comments").asInt(0);
                String thumb = text(d, "thumbnail");

                SocialMediaPost post = new SocialMediaPost();
                post.setId(id);
                post.setPlatform("reddit");
                post.setTitle(title);
                post.setAuthor(author != null ? "u/" + author : null);
                post.setSubreddit(subreddit);
                post.setPermalink(permalink);
                post.setPostUrl(url);
                post.setContent((selftext != null && !selftext.isBlank()) ? selftext : title);
                post.setScore(score);
                post.setNumComments(comments);
                post.setCreatedUtc(createdUtc);
                // image miniature si valide
                if (isHttpUrl(thumb)) {
                    // si tu as un champ mediaUrl dans ton modèle, ajoute-le ici
                    // ex: post.setMediaUrl(thumb);
                }

                out.add(post);
            }
        }
        return out;
    }

    // ========================= Utils =========================

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String t = v.asText();
        return (t == null || t.equals("null")) ? null : t;
    }

    private static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }
}
