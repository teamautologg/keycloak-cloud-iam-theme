package at.autologg.keycloak.loginlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoginLinkWebsiteBeanTest {

    private static final String WEBSITE = LoginLinkWebsiteBean.CLIENT_ID;
    private static final String CALLBACK = "https://www-test.autologg.com/api/auth/callback";

    @Test
    void onlyTheWebsitesClient_getsTheBean() {
        assertNotNull(LoginLinkWebsiteBean.from(WEBSITE, CALLBACK, null, null));
        assertNull(LoginLinkWebsiteBean.from("flutter", "https://logbook.autologg.com/auth.html", null, "a@b.example"));
        assertNull(LoginLinkWebsiteBean.from("flutter", "at.autologg.app:/oauth2redirect", "order", null));
        assertNull(LoginLinkWebsiteBean.from("account-console", CALLBACK, "order", "a@b.example"));
        assertNull(LoginLinkWebsiteBean.from(null, CALLBACK, "order", null));
    }

    @Test
    void linksLeadToTheWebsiteTheLoginCameFrom() {
        LoginLinkWebsiteBean staging = LoginLinkWebsiteBean.from(WEBSITE, CALLBACK, "order", null);
        assertEquals("https://www-test.autologg.com", staging.getOrigin());
        assertEquals("https://www-test.autologg.com/de/shop/fahrtenbuch-lizenz/#kasse", staging.getCheckoutUrl());

        LoginLinkWebsiteBean local = LoginLinkWebsiteBean.from(WEBSITE, "http://LOCALHOST:4331/api/auth/callback?x=1", null, null);
        assertEquals("http://localhost:4331", local.getOrigin());
    }

    @Test
    void aRedirectThatIsNoWebAddress_getsNoBean() {
        assertNull(LoginLinkWebsiteBean.from(WEBSITE, null, "order", null));
        assertNull(LoginLinkWebsiteBean.from(WEBSITE, "at.autologg.app:/oauth2redirect", "order", null));
        assertNull(LoginLinkWebsiteBean.from(WEBSITE, "javascript:alert(1)", "order", null));
        assertNull(LoginLinkWebsiteBean.from(WEBSITE, "https://user@www.autologg.com/api/auth/callback", "order", null));
        assertNull(LoginLinkWebsiteBean.from(WEBSITE, "not a url", "order", null));
    }

    @Test
    void theOrderHintFollowsTheFlowParameter_andWithoutItTheLoginHint() {
        assertTrue(LoginLinkWebsiteBean.from(WEBSITE, CALLBACK, "order", null).isOrder());
        assertFalse(LoginLinkWebsiteBean.from(WEBSITE, CALLBACK, "account", "a@b.example").isOrder());
        assertFalse(LoginLinkWebsiteBean.from(WEBSITE, CALLBACK, "", "a@b.example").isOrder());

        assertTrue(LoginLinkWebsiteBean.from(WEBSITE, CALLBACK, null, "a@b.example").isOrder());
        assertFalse(LoginLinkWebsiteBean.from(WEBSITE, CALLBACK, null, null).isOrder());
        assertFalse(LoginLinkWebsiteBean.from(WEBSITE, CALLBACK, null, " ").isOrder());
    }
}
