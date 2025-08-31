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
    private static final String OAUTH_ALL_SEARCH_URL = "https://oauth.reddit.com/r/all/search";
    private static final String PUBLIC_SEARCH_URL = "https://www.reddit.com/search.json";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String clientId;
    private final String clientSecret; // peut être vide (installed app)
    private final String userAgent;

    // ---- ctor Spring
    public RedditService(
            @Value("${reddit.client-id:${REDDIT_CLIENT_ID:}}") String clientId,
            @Value("${reddit.client-secret:${REDDIT_CLIENT_SECRET:}}") String clientSecret,
            @Value("${reddit.user-agent:${REDDIT_USER_AGENT:AntixBot/1.0 (+https://example.com)}}") String userAgent
    ) {
        this.clientId = nz(clientId);
        this.clientSecret = nz(clientSecret);
        this.userAgent = nz(userAgent, "AntixBot/1.0 (+https://example.com)");
    }
    // ---- ctor no-arg (si tu fais new RedditService() quelque part)
    public RedditService() {
        this.clientId = nz(System.getenv("REDDIT_CLIENT_ID"));
        this.clientSecret = nz(System.getenv("REDDIT_CLIENT_SECRET"));
        this.userAgent = nz(System.getenv("REDDIT_USER_AGENT"), "AntixBot/1.0 (+https://example.com)");
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static String nz(String s, String def) { String v = nz(s); return v.isEmpty() ? def : v; }
    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    @Override public String getPlatformName() { return "reddit"; }

    @Override
    public List<SocialMediaPost> fetchPostsFromTag(String tag, int limit) {
        if (tag == null || tag.isBlank()) return List.of();
        int capped = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));

        System.out.println("[RedditService] cfg: clientId=" + mask(clientId)
                + ", secret=" + (clientSecret.isBlank() ? "(vide)" : "(présent)")
                + ", UA=" + userAgent);

        // 1) OAuth si on a un clientId
        if (!clientId.isBlank()) {
            try {
                Optional<String> tokenOpt = getTokenSmart();
                if (tokenOpt.isPresent()) {
                    String token = tokenOpt.get();
                    System.out.println("[RedditService] ✅ OAuth OK, token reçu. Recherche via oauth.reddit.com …");

                    // Essai A: recherche globale
                    var posts = searchOAuth(OAUTH_SEARCH_URL, tag, capped, token, "global");
                    if (!posts.isEmpty()) return posts;

                    // Essai B: /r/all/search
                    posts = searchOAuth(OAUTH_ALL_SEARCH_URL, tag, capped, token, "r/all");
                    if (!posts.isEmpty()) return posts;

                    // Essai C: tri par nouveauté (parfois renvoie plus)
                    posts = searchOAuth(OAUTH_SEARCH_URL, tag, capped, token, "global-new", "new");
                    if (!posts.isEmpty()) return posts;

                    System.out.println("[RedditService] OAuth a renvoyé 0 résultat pour le tag '" + tag + "'.");
                } else {
                    System.out.println("[RedditService] ⚠️ Aucun token OAuth obtenu (type d'app Reddit/credentials).");
                }
            } catch (Exception e) {
                System.err.println("[RedditService] ❌ OAuth search failed: " + e.getMessage());
            }
        } else {
            System.out.println("[RedditService] ⚠️ Pas de CLIENT_ID → on saute OAuth.");
        }

        // 2) Fallback public (souvent 403/429 en cloud)
        try {
            System.out.println("[RedditService] ↘️ Fallback public JSON …");
            return searchPublicJson(tag, Math.min(capped, 50));
        } catch (Exception e) {
            System.err.println("[RedditService] ❌ Public JSON search failed: " + e.getMessage());
            return List.of();
        }
    }

    // ===== TOKENS =====
    private Optional<String> getTokenSmart() throws IOException, InterruptedException {
        if (!clientSecret.isBlank()) {
            System.out.println("[RedditService] OAuth mode = client_credentials (secret présent)");
            var t = getTokenClientCredentials();
            if (t.isPresent()) return t;
            System.err.println("[RedditService] client_credentials rejeté -> essai fallback installed_client …");
        } else {
            System.out.println("[RedditService] OAuth mode = installed_client (secret vide)");
        }
        // Fallback installed_client (ne requiert PAS de secret)
        return getTokenInstalledClient();
    }


    private Optional<String> getTokenClientCredentials() throws IOException, InterruptedException {
        String credentials = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=client_credentials&scope=read";
        HttpRequest req = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[RedditService] token(client_credentials) status=" + resp.statusCode());
        if (resp.statusCode() / 100 == 2) {
            String token = mapper.readTree(resp.body()).path("access_token").asText(null);
            return Optional.ofNullable(token);
        }
        System.err.println("[RedditService] token client_credentials failed: " + resp.statusCode() + " body=" + resp.body());
        return Optional.empty();
    }

    private Optional<String> getTokenInstalledClient() throws IOException, InterruptedException {
        String credentials = Base64.getEncoder().encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8)); // secret vide
        String form = "grant_type=" + enc("https://oauth.reddit.com/grants/installed_client")
                + "&device_id=DO_NOT_TRACK_THIS_DEVICE&scope=read";
        HttpRequest req = HttpRequest.newBuilder(URI.create(OAUTH_TOKEN_URL))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[RedditService] token(installed_client) status=" + resp.statusCode());
        if (resp.statusCode() / 100 == 2) {
            String token = mapper.readTree(resp.body()).path("access_token").asText(null);
            return Optional.ofNullable(token);
        }
        System.err.println("[RedditService] token installed_client failed: " + resp.statusCode() + " body=" + resp.body());
        return Optional.empty();
    }

    // ===== SEARCH (OAuth) =====
    private List<SocialMediaPost> searchOAuth(String baseUrl, String query, int limit, String bearerToken, String label) throws IOException, InterruptedException {
        return searchOAuth(baseUrl, query, limit, bearerToken, label, "relevance");
    }

    private List<SocialMediaPost> searchOAuth(String baseUrl, String query, int limit, String bearerToken, String label, String sort) throws IOException, InterruptedException {
        String url = baseUrl
                + "?q=" + enc(query)
                + "&limit=" + limit
                + "&sort=" + enc(sort)
                + "&restrict_sr=false"
                + "&raw_json=1"
                + "&include_over_18=on";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.ACCEPT, "application/json")
                .GET().build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[RedditService] search(" + label + ") status=" + resp.statusCode());

        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            System.err.println("[RedditService] search(" + label + ") unauthorized/forbidden, body=" + resp.body());
            return List.of();
        }
        if (resp.statusCode() == 429) {
            System.err.println("[RedditService] search(" + label + ") rate limited (429)");
            return List.of();
        }
        if (resp.statusCode() / 100 != 2) {
            System.err.println("[RedditService] search(" + label + ") failed: " + resp.statusCode() + " body=" + resp.body());
            return List.of();
        }
        return parseListing(resp.body());
    }

    // ===== Fallback PUBLIC =====
    private List<SocialMediaPost> searchPublicJson(String query, int limit) throws IOException, InterruptedException {
        String url = PUBLIC_SEARCH_URL
                + "?q=" + enc(query)
                + "&limit=" + limit
                + "&sort=relevance"
                + "&restrict_sr=false"
                + "&raw_json=1"
                + "&include_over_18=on";

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.USER_AGENT, userAgent)
                .header(HttpHeaders.ACCEPT, "application/json")
                .GET().build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[RedditService] public search status=" + resp.statusCode());
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("Public search failed: " + resp.statusCode());
        return parseListing(resp.body());
    }

    // ===== PARSING =====
    private List<SocialMediaPost> parseListing(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode children = root.path("data").path("children");
        List<SocialMediaPost> out = new ArrayList<>();
        if (children.isArray()) {
            for (JsonNode child : children) {
                JsonNode d = child.path("data");
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
                if (permalink != null && !permalink.isBlank()) p.setPermalink("https://www.reddit.com" + permalink);
                p.setPostUrl(text(d, "url"));

                String selftext = text(d, "selftext");
                p.setContent((selftext != null && !selftext.isBlank()) ? selftext : title);

                p.setScore(d.path("score").asInt(0));
                p.setNumComments(d.path("num_comments").asInt(0));
                p.setCreatedUtc(d.path("created_utc").asLong(0));

                out.add(p);
            }
        }
        System.out.println("[RedditService] parse: " + out.size() + " posts");
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String t = v.asText();
        return (t == null || "null".equals(t)) ? null : t;
    }

    private static String mask(String s) {
        if (s == null || s.isBlank()) return "(vide)";
        if (s.length() <= 6) return s.charAt(0) + "****";
        return s.substring(0,3) + "***" + s.substring(s.length()-3);
    }
}
