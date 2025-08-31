package antix.model;

/**
 * Modèle spécifique Reddit.
 * On laisse SocialMediaPost fournir les getters de base
 * (score/int, numComments/int, createdUtc/long, tags/List<String>, etc.).
 * N'ajoute ici que des champs annexes si nécessaire.
 */
public class RedditPost extends SocialMediaPost {

    // Champs optionnels (utilise-les si tu en as besoin dans l'UI)
    private String thumbnailUrl;
    private boolean over18;
    private String flairText;

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public boolean isOver18() { return over18; }
    public void setOver18(boolean over18) { this.over18 = over18; }

    public String getFlairText() { return flairText; }
    public void setFlairText(String flairText) { this.flairText = flairText; }
}
