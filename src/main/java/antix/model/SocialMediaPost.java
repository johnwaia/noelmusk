package antix.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SocialMediaPost {

    // --- Champs de base (utilisés partout) ---
    private String id;
    private String platform;      // "reddit", "mastodon", ...
    private String title;
    private String author;        // "u/foo", "@bar@instance", ...
    private String subreddit;     // pour Reddit
    private String permalink;     // lien interne (reddit)
    private String postUrl;       // lien externe si dispo
    private String content;       // texte/HTML
    private int score;            // upvotes/likes
    private int numComments;      // replies/comments
    private long createdUtc;      // epoch seconds

    // Champs pour features UI
    private List<String> tags = new ArrayList<>();
    private int likeCount = -1;   // -1 => déduire de score
    private int shareCount = 0;

    // Optionnel: logo & couleurs spécifiques
    private String logoPath;      // peut rester null
    private String badgeColor;    // "#RRGGBB" ou "var(--...)" etc.
    private String badgeTextColor;

    // ======= Getters/Setters classiques =======
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

    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    public void setTags(List<String> tags) {
        this.tags = (tags == null) ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public int getLikeCount() { return likeCount >= 0 ? likeCount : score; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }

    public int getShareCount() { return shareCount; }
    public void setShareCount(int shareCount) { this.shareCount = shareCount; }

    public String getLogoPath() { return (logoPath != null) ? logoPath : defaultLogoForPlatform(); }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }

    public String getBadgeColor() { return (badgeColor != null) ? badgeColor : defaultBadgeColor(); }
    public void setBadgeColor(String badgeColor) { this.badgeColor = badgeColor; }

    public String getBadgeTextColor() { return (badgeTextColor != null) ? badgeTextColor : "#ffffff"; }
    public void setBadgeTextColor(String badgeTextColor) { this.badgeTextColor = badgeTextColor; }

    // ======= Helpers attendus par PlatformBadge / GridUtils / Commands =======
    public String getPlatformDisplayName() {
        if ("reddit".equalsIgnoreCase(platform)) return "Reddit";
        if ("mastodon".equalsIgnoreCase(platform)) return "Mastodon";
        return platform != null ? capitalize(platform) : "Social";
    }

    public String getPlatformShortCode() {
        if ("reddit".equalsIgnoreCase(platform)) return "RD";
        if ("mastodon".equalsIgnoreCase(platform)) return "MA";
        return "SO";
    }

    public String getUrl() {
        return postUrl != null && !postUrl.isBlank() ? postUrl : permalink;
    }

    public int getRepliesCount() { return numComments; }

    // Texte d’affichage
    public String getDisplayName() {
        if (author != null && subreddit != null && !subreddit.isBlank()) {
            return author + " in r/" + subreddit;
        }
        return author != null ? author : (title != null ? title : "Post");
    }

    public String getPlatformInfo() {
        String p = getPlatformDisplayName();
        if (subreddit != null && !subreddit.isBlank()) return p + " · r/" + subreddit;
        return p;
    }

    public String getEngagementText() { return "▲ " + getLikeCount() + " · 💬 " + numComments; }
    public String getScoreText() { return "▲ " + getLikeCount(); }

    public Instant getCreatedAt() {
        return createdUtc > 0 ? Instant.ofEpochSecond(createdUtc) : null;
    }

    public String getFormattedDate() {
        Instant ts = getCreatedAt();
        if (ts == null) return "";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.of("Europe/Paris"));
        return fmt.format(ts);
    }

    // ======= Defaults par plateforme =======
    private String defaultLogoForPlatform() {
        if ("reddit".equalsIgnoreCase(platform)) return "/icons/reddit.svg";
        if ("mastodon".equalsIgnoreCase(platform)) return "/icons/mastodon.svg";
        return "/icons/link.svg";
    }

    private String defaultBadgeColor() {
        if ("reddit".equalsIgnoreCase(platform)) return "#FF4500";
        if ("mastodon".equalsIgnoreCase(platform)) return "#6364FF";
        return "#666666";
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        return s.substring(0,1).toUpperCase() + s.substring(1);
    }
}
