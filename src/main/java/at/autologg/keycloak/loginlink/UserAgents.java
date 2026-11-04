package at.autologg.keycloak.loginlink;

import java.util.Locale;

/**
 * Coarse browser and operating system of a User-Agent header, shown with the
 * request ("Chrome on Windows") so the recipient can tell whether they started
 * the login. Unknown parts come back as {@code null}.
 */
final class UserAgents {

    private UserAgents() {
    }

    static String browser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent;
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) {
            return "Edge";
        }
        if (ua.contains("OPR/") || ua.contains("Opera")) {
            return "Opera";
        }
        if (ua.contains("SamsungBrowser/")) {
            return "Samsung Internet";
        }
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) {
            return "Firefox";
        }
        if (ua.contains("CriOS/") || ua.contains("Chrome/")) {
            return "Chrome";
        }
        if (ua.contains("Safari/") && (ua.contains("Version/") || ua.contains("iPhone") || ua.contains("iPad"))) {
            return "Safari";
        }
        return null;
    }

    /**
     * The login ends in an Android app through its own scheme. A custom tab in the background drops the redirect
     * to the app and the login it carries is used up (seen in Chrome 134: the page stays on "confirmed"), and Chrome
     * documents that it opens apps only after a user gesture. So the page must not send the login off by itself
     * there. iOS is not affected, the system's login sheet picks the redirect up itself; https stays in the browser.
     */
    static boolean finishesWithTap(String userAgent, String redirectUri) {
        if (redirectUri == null || !redirectUri.contains(":") || !"Android".equals(operatingSystem(userAgent))) {
            return false;
        }
        String lower = redirectUri.toLowerCase(Locale.ROOT);
        return !lower.startsWith("http://") && !lower.startsWith("https://");
    }

    static String operatingSystem(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String ua = userAgent;
        if (ua.contains("iPhone")) {
            return "iPhone";
        }
        if (ua.contains("iPad")) {
            return "iPad";
        }
        if (ua.contains("Android")) {
            return "Android";
        }
        if (ua.contains("Windows")) {
            return "Windows";
        }
        if (ua.contains("CrOS")) {
            return "ChromeOS";
        }
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
            return "macOS";
        }
        if (ua.contains("Linux")) {
            return "Linux";
        }
        return null;
    }
}
