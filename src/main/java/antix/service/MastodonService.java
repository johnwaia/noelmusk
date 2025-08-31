package antix.service;

import antix.model.SocialMediaPost;
import antix.model.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MastodonService implements SocialMediaService {

    // Instance par défaut (surchageable par env var)
    private final String instanceDomain;
    private final String userAgent;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public MastodonService(
            @Value("${mastodon.instance:${MASTODON_INSTANCE:mastodon.social}}") String instanceDomain,
            @Value("${mastodon.user-agent:${MASTODON_USER_AGENT:AntixBot/1.0 (+https://example.com)}}") String userAgent
    ) {
        this.instanceDomain = (instanceDomain == null || instanceDomain.isBlank())
                ? "mastodon.social" : instanceDomain.trim();
        this.userAgent = (userAgent == null || userAgent.isBlank())
                ? "AntixBot/1.0 (+https://example.com)" : userAgent.trim();
    }

    // Constructeur sans argument si tu instancies à la main
    public MastodonService() {
        String inst = System.getenv("MASTODON_INSTANCE");
        String ua = System.getenv("MASTODON_USER_AGENT");
        this.instanceDomain = (inst == null || inst.isBlank()) ? "mastodon.social" : inst.trim();
        this.userAgent = (ua == null || ua.isBlank()) ? "AntixBot/1.0 (+https://example.com)" : ua.trim();
    }

    @Override
    public String getPlatformName() {
        return "mastodon";
    }

    @Override
    public List<SocialMediaPost> fetchPostsFromTag(String tag, int limit) {
        if (tag == null || tag.isBlank()) return List.of();
        int capped = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 80));

        // API officielle: /api/v1/timelines/tag/{hashtag}?limit=N
        // Retourne un tableau de "Status"
        String encodedTag = URLEncoder.encode(tag.replaceFirst("^#", ""), StandardCharsets.UTF_8);
        String url = "https://" + instanceDomain + "/api/v1/timelines/tag/" + encodedTag + "?limit=" + capped;

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .GET().build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.err.println("[MastodonService] HTTP " + resp.statusCode() + " body=" + resp.body());
                return List.of();
            }

            JsonNode arr = mapper.readTree(resp.body());
            if (!arr.isArray()) return List.of();

            List<SocialMediaPost> out = new ArrayList<>();
            for (JsonNode s : arr) {
                SocialMediaPost p = new SocialMediaPost();
                p.setPlatform("mastodon");
                p.setId(s.path("id").asText(null));

                // contenu (HTML)
                p.setContent(s.path("content").asText(""));

                // URL du statut
                String statusUrl = s.path("url").asText(null);
                if (statusUrl == null || statusUrl.isBlank()) {
                    // fallback : certaines instances ont 'uri'
                    statusUrl = s.path("uri").asText(null);
                }
                p.setPostUrl(statusUrl);
                p.setPermalink(statusUrl);

                // auteur
                JsonNode acct = s.path("account");
                String handle = acct.path("acct").asText(null); // ex: user@instance
                if (handle == null || handle.isBlank()) handle = acct.path("username").asText(null);
                if (handle != null && !handle.startsWith("@")) handle = "@" + handle;
                p.setAuthor(handle);

                // métriques
                p.setNumComments(s.path("replies_count").asInt(0));
                p.setShareCount(s.path("reblogs_count").asInt(0));
                p.setLikeCount(s.path("favourites_count").asInt(0)); // likeCount ≈ favs

                // date
                String createdAt = s.path("created_at").asText(null);
                if (createdAt != null && !createdAt.isBlank()) {
                    try {
                        long epoch = OffsetDateTime.parse(createdAt).toInstant().getEpochSecond();
                        p.setCreatedUtc(epoch);
                    } catch (Exception ignore) { /* format inattendu */ }
                }

                // tags (array d'objets { name, url })
                JsonNode tagsArr = s.path("tags");
                if (tagsArr.isArray()) {
                    List<Tag> tags = new ArrayList<>();
                    for (JsonNode t : tagsArr) {
                        String name = t.path("name").asText(null);
                        if (name != null && !name.isBlank()) tags.add(new Tag(name));
                    }
                    p.setTags(tags);
                }

                // titre facultatif (pour un rendu harmonieux si besoin)
                if (p.getTitle() == null || p.getTitle().isBlank()) {
                    // Petite "headline" basée sur l’auteur
                    p.setTitle((p.getAuthor() != null ? p.getAuthor() + ": " : "") + "Post Mastodon");
                }

                out.add(p);
            }
            return out;

        } catch (Exception e) {
            System.err.println("[MastodonService] Erreur: " + e.getMessage());
            return List.of();
        }
    }
}
