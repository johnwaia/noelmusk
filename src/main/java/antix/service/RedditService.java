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
import java.util.*;
import java.util.Base64;

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

    // --- ctor Spring (3 args) ---
    public RedditService(
            @Value("${reddit.client-id:${REDDIT_CLIENT_ID:}}") String clientId,
            @Value("${reddit.client-secret:${REDDIT_CLIENT_SECRET:}}") String clientSecret,
            @Value("${reddit.user-agent:${REDDIT_USER_AGENT:AntixBot/1.0 (+https://example.com)}}") String userAgent
    ) {
        this.clientId = safe(clientId);
        this.clientSecret = safe(clientSecret);
        this.userAgent = safe(userAgent, "AntixBot/1.0 (+https://example.com)");
    }

    // --- ctor sans argument (pour new RedditService() dans MainView/Factory) ---
    public RedditService() {
        this.clientId = safe(System.getenv("REDDIT_CLIENT_ID"));
        this.clientSecret = safe(System.getenv("REDDIT_CLIENT_SECRET"));
        this.userAgent = safe(System.getenv("REDDIT_USER_AGENT"), "AntixBot/1.0 (+https://example.com)");
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String safe(String s, String def) { String v = safe(s); return v.isEmpty()?def:v; }

    @Override public String getPlatformName() { return "reddit"; }

    @Override
    public List<SocialMediaPost> fetchPostsFromTag(String tag, int limit) {
        if (tag == null || tag.isBlank()) return List.of();
        int capped = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));

        // OAuth si credentials présents
        if (!clientId.isBlank() && !clientSecret.isBlank()) {
            try {
                var token = getAppOnlyToken().orElse(null);
                if (token != null) {
                    var posts = searchWithOAuth(tag, capped, token);
                    if (!posts.isEmpty()) return posts;
                }
            } catch (Exception e) {
                System.err.println("[RedditService] OAuth failed, fallback to public: " + e.getMessage());
            }
        }

        // Repli JSON public
        try {
            return searchPublicJson(tag, Math.min(capped, 50));
        } catch (Exception e) {
            System.err.println("[RedditService] Public JSON failed: " + e.getMessage());
            return List.of();
        }
    }

    // ===== OAuth =====
    private Optional<String> getAppOnlyToken() throws IOException, InterruptedException {
        String credentials = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 == 2) {
            String token = mapper.readTree(resp.body()).path("access_token").asText(null);
            return Optional.ofNullable(token);
        }
        System.err.println("[RedditService] Token request failed: " + resp.statusCode() + " body=" + resp.body());
        return Optional.empty();
    }

    private List<SocialMediaPost> searchWithOAuth(String query, int limit, String bearerToken) throws IOException, InterruptedException {
        String url = OAUTH_SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + limit + "&sort=relevance&type=link&restrict_sr=false";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 401 || resp.statusCode() == 403) throw new IllegalStateException("Unauthorized/Forbidden");
        if (resp.statusCode() == 429) throw new IllegalStateException("Rate limited (429)");
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("OAuth search failed: " + resp.statusCode());
        return parse(resp.body());
    }

    // ===== Public JSON fallback =====
    private List<SocialMediaPost> searchPublicJson(String query, int limit) throws IOException, InterruptedException {
        String url = PUBLIC_SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + limit + "&sort=relevance&type=link&restrict_sr=false";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.USER_AGENT, userAgent).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) throw new IllegalStateException("Rate limited (429)");
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("Public search failed: " + resp.statusCode());
        return parse(resp.body());
    }

    private List<SocialMediaPost> parse(String body) throws IOException {
        var root = mapper.readTree(body);
        var children = root.path("data").path("children");
        List<SocialMediaPost> out = new ArrayList<>();
        if (children.isArray()) {
            for (var c : children) {
                var d = c.path("data");
                var p = new SocialMediaPost();
                p.setId(text(d, "id"));
                p.setPlatform("reddit");
                p.setTitle(text(d, "title"));
                var author = text(d, "author");
                p.setAuthor(author != null ? "u/" + author : null);
                p.setSubreddit(text(d, "subreddit"));
                p.setPermalink("https://www.reddit.com" + text(d, "permalink"));
                p.setPostUrl(text(d, "url"));
                p.setContent(opt(text(d, "selftext"), p.getTitle()));
                p.setScore(d.path("score").asInt(0));
                p.setNumComments(d.path("num_comments").asInt(0));
                p.setCreatedUtc(d.path("created_utc").asLong(0));
                out.add(p);
            }
        }
        return out;
    }

    private static String text(JsonNode n, String f) {
        var v = n.path(f); if (v.isMissingNode() || v.isNull()) return null;
        String t = v.asText(); return (t == null || "null".equals(t)) ? null : t;
    }
    private static String opt(String v, String def) { return (v == null || v.isBlank()) ? def : v; }
}
