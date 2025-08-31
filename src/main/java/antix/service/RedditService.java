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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

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

    // --- Constructeur Spring (injection de propriétés/env vars) ---
    public RedditService(
            @Value("${reddit.client-id:${REDDIT_CLIENT_ID:}}") String clientId,
            @Value("${reddit.client-secret:${REDDIT_CLIENT_SECRET:}}") String clientSecret,
            @Value("${reddit.user-agent:${REDDIT_USER_AGENT:AntixBot/1.0 (+https://example.com)}}") String userAgent
    ) {
        this.clientId = safe(clientId);
        this.clientSecret = safe(clientSecret);
        this.userAgent = safe(userAgent, "AntixBot/1.0 (+https://example.com)");
    }

    // --- Constructeur sans argument (pour new RedditService()) ---
    public RedditService() {
        this.clientId = safe(System.getenv("REDDIT_CLIENT_ID"));
        this.clientSecret = safe(System.getenv("REDDIT_CLIENT_SECRET"));
        this.userAgent = safe(System.getenv("REDDIT_USER_AGENT"), "AntixBot/1.0 (+https://example.com)");
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String safe(String s, String def) { String v = safe(s); return v.isEmpty() ? def : v; }

    @Override
    public String getPlatformName() {
        return "reddit";
    }

    @Override
    public List<SocialMediaPost> fetchPostsFromTag(String tag, int limit) {
        if (tag == null || tag.isBlank()) return List.of();
        int capped = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));

        // 1) OAuth si credentials présents
        if (!clientId.isBlank() && !clientSecret.isBlank()) {
            try {
                Optional<String> tokenOpt = getAppOnlyToken(); // scope=read
                if (tokenOpt.isPresent()) {
                    List<SocialMediaPost> posts = searchWithOAuth(tag, capped, tokenOpt.get());
                    if (!posts.isEmpty()) return posts;
                }
            } catch (Exception e) {
                System.err.println("[RedditService] OAuth search failed, fallback to public JSON: " + e.getMessage());
            }
        }

        // 2) Fallback JSON public (peut être bloqué 403/429 en cloud)
        try {
            return searchPublicJson(tag, Math.min(capped, 50));
        } catch (Exception e) {
            System.err.println("[RedditService] Public JSON search failed: " + e.getMessage());
            return List.of();
        }
    }

    // =================== OAuth ===================

    private Optional<String> getAppOnlyToken() throws IOException, InterruptedException {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        // Ajout de scope=read
        String form = "grant_type=client_credentials&scope=read";

        HttpRequest req = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 == 2) {
            JsonNode json = mapper.readTree(resp.body());
            String token = json.path("access_token").asText(null);
            return Optional.ofNullable(token);
        }
        System.err.println("[RedditService] Token request failed: " + resp.statusCode() + " body=" + resp.body());
        return Optional.empty();
    }

    private List<SocialMediaPost> searchWithOAuth(String query, int limit, String bearerToken)
            throws IOException, InterruptedException {

        String url = OAUTH_SEARCH_URL
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + limit
                + "&sort=relevance"
                + "&restrict_sr=false"
                + "&raw_json=1"
                + "&include_over_18=on"; // pas de type=link => plus de résultats

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.ACCEPT, "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new IllegalStateException("Unauthorized/Forbidden: " + resp.statusCode());
        }
        if (resp.statusCode() == 429) {
            throw new IllegalStateException("Rate limited (429) by Reddit API");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Reddit OAuth search failed: " + resp.statusCode());
        }

        return parseListing(resp.body());
    }

    // ============ Fallback JSON public ============

    private List<SocialMediaPost> searchPublicJson(String query, int limit)
            throws IOException, InterruptedException {

        String url = PUBLIC_SEARCH_URL
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + limit
                + "&sort=relevance"
                + "&restrict_sr=false"
                + "&raw_json=1"
                + "&include_over_18=on";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.ACCEPT, "application/json")
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() == 429) {
            throw new IllegalStateException("Rate limited (429) by reddit.com public JSON");
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Public search failed: " + resp.statusCode());
        }

        return parseListing(resp.body());
    }

    // =================== Parsing commun ===================

    private List<SocialMediaPost> parseListing(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode children = root.path("data").path("children");
        List<SocialMediaPost> out = new ArrayList<>();

        if (children.isArray()) {
            for (JsonNode child : children) {
                JsonNode d = child.path("data");
                // ne garder que les posts (kind=t3 en général) — mais on reste permissif
                String title = text(d, "title");
                if (title == null || title.isBlank()) continue;

                SocialMediaPost p = new SocialMediaPost();
                p.setId(text(d, "id"));
                p.setPlatform("reddit");
                p.setTitle(title);

                String author = text(d, "author");
                p.setAuthor(author != null && !author.isBlank() ? "u/" + author : null);

                p.setSubreddit(text(d, "subreddit"));

                String permalink = text(d, "permalink");
                if (permalink != null && !permalink.isBlank()) {
                    p.setPermalink("https://www.reddit.com" + permalink);
                }
                p.setPostUrl(text(d, "url"));

                String selftext = text(d, "selftext");
                p.setContent((selftext != null && !selftext.isBlank()) ? selftext : title);

                p.setScore(d.path("score").asInt(0));
                p.setNumComments(d.path("num_comments").asInt(0));
                p.setCreatedUtc(d.path("created_utc").asLong(0));

                out.add(p);
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String t = v.asText();
        return (t == null || "null".equals(t)) ? null : t;
        }
}
