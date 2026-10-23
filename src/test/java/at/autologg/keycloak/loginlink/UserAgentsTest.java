package at.autologg.keycloak.loginlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UserAgentsTest {

    private static final String CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
    private static final String EDGE_WINDOWS = CHROME_WINDOWS + " Edg/141.0.0.0";
    private static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1";
    private static final String CHROME_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/141.0.0.0 Mobile/15E148 Safari/604.1";
    private static final String CHROME_ANDROID = "Mozilla/5.0 (Linux; Android 16; Pixel 8a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36";
    private static final String FIREFOX_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15.6; rv:143.0) Gecko/20100101 Firefox/143.0";
    private static final String SAFARI_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    @Test
    void recognisesCommonBrowsers() {
        assertEquals("Chrome", UserAgents.browser(CHROME_WINDOWS));
        assertEquals("Edge", UserAgents.browser(EDGE_WINDOWS));
        assertEquals("Safari", UserAgents.browser(SAFARI_IPHONE));
        assertEquals("Chrome", UserAgents.browser(CHROME_IPHONE));
        assertEquals("Chrome", UserAgents.browser(CHROME_ANDROID));
        assertEquals("Firefox", UserAgents.browser(FIREFOX_MAC));
        assertEquals("Safari", UserAgents.browser(SAFARI_MAC));
    }

    @Test
    void recognisesOperatingSystems() {
        assertEquals("Windows", UserAgents.operatingSystem(CHROME_WINDOWS));
        assertEquals("iPhone", UserAgents.operatingSystem(SAFARI_IPHONE));
        assertEquals("Android", UserAgents.operatingSystem(CHROME_ANDROID));
        assertEquals("macOS", UserAgents.operatingSystem(FIREFOX_MAC));
    }

    @Test
    void unknownAgents() {
        assertNull(UserAgents.browser(null));
        assertNull(UserAgents.browser("curl/8.9.1"));
        assertNull(UserAgents.operatingSystem("curl/8.9.1"));
    }
}
