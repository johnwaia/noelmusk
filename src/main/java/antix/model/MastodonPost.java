package antix.model;

import java.time.Instant;

public class MastodonPost extends SocialMediaPost {

    private Account account;   // assure-toi d’avoir antix.model.Account
    private String language;
    private Instant createdAt; // propre à Mastodon

    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Instant getCreatedAtInstant() {
        if (createdAt != null) return createdAt;
        return super.getCreatedAt();
    }
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        if (createdAt != null) super.setCreatedUtc(createdAt.getEpochSecond());
    }
}
