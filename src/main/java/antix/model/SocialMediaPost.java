package antix.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SocialMediaPost {

    private String id;
    private String platform;
    private String title;
    private String author;
    private String subreddit;
    private String permalink;
    private String postUrl;
    private String content;
    private int score;
    private int numComments;
    private long createdUtc;

    private List<Tag> tags = new ArrayList<>();

    private int likeCount = -1;
    private int shareCount = 0;

    private String logoPath;
    private String badgeColor;
    private String badgeTextColor;

    // base
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

    // tags
    public List<Tag> getTags() { return Collections.unmodifiableList(tags); }
    public void setTags(List<Tag> tags) { this.tags = (tags == null) ? new ArrayList<>() : new ArrayList<>(tags); }
    public void addTag(String name) { if (name != null && !name.isBlank()) this.tags.add(new Tag(name)); }
    public void addTag(Tag tag) { if (tag != null) this.tags.add(tag); }

    // métriques
    public int getLikeCount() { return likeCount >= 0 ? likeCount : score; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
    public int getShareCount() { return shareCount; }
    public void setShareCount(int shareCount) { this.shareCount = shareCount; }
    public int getRepliesCount() { return numComments; }

    // helpers UI
    public String getLogoPath() {
        if (logoPath != null) return logoPath;
        if ("reddit".equalsIgnoreCase(platform)) return "/icons/reddit.svg";
        if ("mastodon".equalsIgnoreCase(platform)) return "/icons/mastodon.svg";
        return "/icons/link.svg";
    }
    public void setLogoPath(String logoPath) { this.logoPath = logoPath; }

    public String getBadgeColor() {
        if (badgeColor != null) return badgeColor;
        if ("reddit".equalsIgnoreCase(platform)) return "#FF4500";
        if ("mastodon".equalsIgnoreCase(platform)) return "#6364FF";
        return "#666666";
    }
    public void setBadgeColor(String badgeColor) { this.badgeColor = badgeColor; }

    public String getBadgeTextColor() { return badgeTextColor != null ? badgeTextColor : "#ffffff"; }
    public void setBadgeTextColor(String badgeTextColor) { this.badgeTextColor = badgeTextColor; }

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
    public String getUrl() { return (postUrl != null && !postUrl.isBlank()) ? postUrl : permalink; }

    public String getDisplayName() {
        if (author != null && subreddit != null && !subreddit.isBlank()) return author + " in r/" + subreddit;
        return author != null ? author : (title != null ? title : "Post");
    }
    public String getPlatformInfo() {
        String p = getPlatformDisplayName();
        return (subreddit != null && !subreddit.isBlank()) ? (p + " · r/" + subreddit) : p;
    }
    public String getEngagementText() { return "▲ " + getLikeCount() + " · 💬 " + numComments; }
    public String getScoreText() { return "▲ " + getLikeCount(); }

    public Instant getCreatedAt() { return createdUtc > 0 ? Instant.ofEpochSecond(createdUtc) : null; }
    public String getFormattedDate() {
        Instant ts = getCreatedAt(); if (ts == null) return "";
        var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Europe/Paris"));
        return fmt.format(ts);
    }

    private static String capitalize(String s) {
        return (s == null || s.isBlank()) ? "" : s.substring(0,1).toUpperCase()+s.substring(1);
    }
}
