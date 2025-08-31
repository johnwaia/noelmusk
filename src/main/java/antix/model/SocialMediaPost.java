package antix.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Modèle simple pour afficher des posts (Reddit, Mastodon, etc.) dans Vaadin.
 * Compatible avec le code existant (setters/getters utilisés dans tes services et commandes).
 */
public class SocialMediaPost {

    // --- Champs principaux ---
    private String id;             // ex: t3_xxxxx (reddit) ou URL unique
    private String platform;       // "reddit" | "mastodon" | ...
    private String title;
    private String author;         // ex: "u/foo" ou "@bar@instance"
    private String subreddit;      // pour Reddit (peut rester vide)
    private String permalink;      // lien interne (reddit: /r/.../comments/...)
    private String postUrl;        // lien externe si présent
    private String content;        // HTML (ton UI utilise innerHTML)
    private int score;             // upvotes / favs
    private int numComments;       // nb de réponses/commentaires
    private long createdUtc;       // epoch seconds (utilisé pour le tri & affichage)

    // --- Getters/Setters "bean" (Vaadin Grid les détecte) ---
    public SocialMediaPost() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getSubreddit() { return subreddit; }
    public void setSubreddit(String subreddit) { this.subreddit = subreddit; }

    public String getPermalink() { return permalink; }
    public void setPermalink(String permalink) { this.permalink = permalink; }

    public String getPostUrl() { return postUrl; }
    public void setPostUrl(String postUrl) { this.postUrl = postUrl; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public int getNumComments() { return numComments; }
    public void setNumComments(int numComments) { this.numComments = numComments; }

    public long getCreatedUtc() { return createdUtc; }
    public void setCreatedUtc(long createdUtc) { this.createdUtc = createdUtc; }

    // --- Méthodes utilitaires attendues par ton UI/commandes ---

    /** Date sous forme Instant (utilisé pour le tri dans MainView). */
    public Instant getCreatedAt() {
        return createdUtc > 0 ? Instant.ofEpochSecond(createdUtc) : null;
    }

    /** Affichage "sympa" pour l’UI (MainView appelle getDisplayName). */
    public String getDisplayName() {
        if (author != null && subreddit != null && !subreddit.isBlank()) {
            return author + " in r/" + subreddit;
        }
        return author != null ? author : (title != null ? title : "Post");
    }

    /** "Reddit · r/java" par ex. (MainView appelle getPlatformInfo). */
    public String getPlatformInfo() {
        String p = platform != null ? capitalize(platform) : "Social";
        if (subreddit != null && !subreddit.isBlank()) {
            return p + " · r/" + subreddit;
        }
        return p;
    }

    /** "▲123 · 💬 45" (MainView appelle getEngagementText). */
    public String getEngagementText() {
        return "▲ " + score + " · 💬 " + numComments;
    }

    /** "▲ 123" (MainView appelle getScoreText). */
    public String getScoreText() {
        return "▲ " + score;
    }

    /** Date formatée (MainView appelle getFormattedDate). */
    public String getFormattedDate() {
        Instant ts = getCreatedAt();
        if (ts == null) return "";
        // Europe/Paris comme ton fuseau
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.of("Europe/Paris"));
        return fmt.format(ts);
    }

    /** Certains filtres utilisent getReplies() (FilterCommand). */
    public int getReplies() {
        return numComments;
    }

    // --- Egalité sur l’id + plateforme (utile pour favoris, etc.) ---
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SocialMediaPost)) return false;
        SocialMediaPost that = (SocialMediaPost) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(platform, that.platform);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, platform);
    }

    // --- Util ---
    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        return s.substring(0,1).toUpperCase() + s.substring(1);
    }
}
