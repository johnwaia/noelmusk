package antix.factory;

import antix.service.RedditService;
import antix.service.SocialMediaService;

public class SocialMediaServiceFactory {
  public static SocialMediaService forPlatform(String platform) {
    if (platform == null) return new RedditService();
    switch (platform.toLowerCase()) {
      case "reddit":   return new RedditService();
      default:         return new RedditService();
    }
  }
}
