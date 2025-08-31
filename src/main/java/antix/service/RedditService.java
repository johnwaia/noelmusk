package antix.service;

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
 * Reddit search via official OAuth API with a resilient fallback to public JSON.
 * Requires env/properties:
 *   reddit.client-id
 *   reddit.client-secret
 *   reddit.user-agent
 */
@Service
public class RedditService implements SocialMediaService {

    private static final String OAUTH_TOKEN_URL = "https://www.reddit.com/api/v1/access_token";
    private static final String OAUTH_SEARCH_URL = "https://oauth.reddit.com/search";
    private static final String PUBLIC_SEARCH_URL = "https://www.reddit.com/search.json";

    private final HttpClient http;
    private final ObjectMapper mapper;

    private final String clientId;
    private final String clientSecret;
    private final String userAgent;

    public RedditService(
            @Value("${reddit.client-id:${REDDIT_CLIENT_ID:}}") String clientId,
            @Value("${reddit.client-secret:${REDDIT_CLIENT_SECRET:}}") String clientSecret,
            @Value("${reddit.user-agent:${REDDIT_USER_AGENT:AntixBot/1.0 (+https://example.com)}}") String userAgent
    ) {
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.userAgent = userAgent == null ? "AntixBot/1.0 (+https://example.com)" : userAgent.trim();
    }

    // ---- Public API ---------------------------------------------------------

    @Override
    public List<SocialMediaPost> search(String query) {
        return search(query, 20);
    }

    public List<SocialMediaPost> search(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();

        // 1) Essayer OAuth (fiable en prod)
        if (hasCredentials()) {
            try {
                String token = getAppOnlyToken()
                        .orElseThrow(() -> new IllegalStateException("Reddit OAuth: token absent"));
                List<SocialMediaPost> posts = searchWithOAuth(query, limit, token);
                if (!posts.isEmpty()) return posts;
            } catch (Exception e) {
                // journaliser et tomber en repli
                System.err.println("[RedditService] OAuth search failed, falling back to public API: " + e.getMessage());
            }
        }

        // 2) Fallback API publique JSON (peut renvoyer 429 en cloud)
        try {
            return searchPublicJson(query, limit);
        } catch (Exception e) {
            System.err.println("[RedditService] Public JSON search failed: " + e.getMessage());
            return List.of();
        }
    }

    // ---- OAuth flow ---------------------------------------------------------

    private boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    private Optional<String> getAppOnlyToken() throws IOException, InterruptedException {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        String form = "grant_type=client_credentials"; // app-only
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

    private List<SocialMediaPost> searchWithOAuth(String query, int limit, String bearerToken) throws IOException, InterruptedException {
        String url = OAUTH_SEARCH_URL
                + "?q=" + url(query)
                + "&limit=" + Math.max(1, Math.min(limit, 100))
                + "&sort=relevance&type=link&restrict_sr=false";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new IllegalStateException("Unauthorized/Forbidden: check Reddit credentials & scopes");
        }
        if (resp.statusCode() == 429) {
            throw new IllegalStateException("Rate limited (429) by Reddit API");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Reddit OAuth search failed: " + resp.statusCode());
        }

        return parseRedditListing(resp.body());
    }

    // ---- Public JSON fallback ----------------------------------------------

    private List<SocialMediaPost> searchPublicJson(String query, int limit) throws IOException, InterruptedException {
        String url = PUBLIC_SEARCH_URL
                + "?q=" + url(query)
                + "&limit=" + Math.max(1, Math.min(limit, 50))
                + "&sort=relevance&type=link&restrict_sr=false";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.USER_AGENT, userAgent)
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 429) {
            throw new IllegalStateException("Rate limited (429) by reddit.com public JSON");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("Public JSON search failed: " + resp.statusCode());
        }

        return parseRedditListing(resp.body());
    }

    // ---- Parsing ------------------------------------------------------------

    private List<SocialMediaPost> parseRedditListing(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode children = root.path("data").path("children");
        List<SocialMediaPost> out = new ArrayList<>();

        if (children.isArray()) {
            for (JsonNode child : children) {
                JsonNode d = child.path("data");
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

                // TODO: adapte ce mapping selon ta classe SocialMediaPost
                SocialMediaPost post = SocialMediaPost.builder()
                        .platform("reddit")
                        .author(author)
                        .title(title)
                        .content(selftext != null && !selftext.isBlank() ? selftext : title)
                        .link(permalink)
                        .mediaUrl(isHttpUrl(thumb) ? thumb : null)
                        .externalUrl(url)
                        .score(score)
                        .commentsCount(comments)
                        .subgroup(subreddit)                 // ex: r/java
                        .publishedAt(createdUtc > 0 ? Instant.ofEpochSecond(createdUtc) : null)
                        .build();

                out.add(post);
            }
        }
        return out;
    }

    // ---- Utils --------------------------------------------------------------

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
