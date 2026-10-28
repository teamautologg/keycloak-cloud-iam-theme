package at.autologg.keycloak.loginlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticatorConfigModel;

class LoginLinkConfigTest {

    @Test
    void defaultsWithoutConfig() {
        LoginLinkConfig config = LoginLinkConfig.from(null);
        assertEquals(15, config.linkTtlMinutes);
        assertEquals(6, config.codeLength);
        assertEquals(5, config.maxCodeAttempts);
        assertEquals(30, config.resendCooldownSeconds);
        assertEquals(10, config.maxSendsPerHour);
        assertEquals(30, config.maxSendsPerIpPerHour);
        assertTrue(config.excludedIdentityProviders.isEmpty());
        assertTrue(config.markEmailVerified);
        assertFalse(config.passwordLogin);
        assertTrue(config.excludedRoles.isEmpty());
        assertTrue(config.excludedDomains.isEmpty());
        assertTrue(config.emailFirst);
    }

    @Test
    void emailFirstCanBeSwitchedOff() {
        assertFalse(LoginLinkConfig.from(model(Map.of(LoginLinkConfig.EMAIL_FIRST, "false"))).emailFirst);
    }

    @Test
    void passwordLoginIsOffUnlessSwitchedOn() {
        assertTrue(LoginLinkConfig.from(model(Map.of(LoginLinkConfig.PASSWORD_LOGIN, "true"))).passwordLogin);
        assertFalse(LoginLinkConfig.from(model(Map.of(LoginLinkConfig.PASSWORD_LOGIN, "false"))).passwordLogin);
        assertFalse(LoginLinkConfig.from(model(Map.of(LoginLinkConfig.PASSWORD_LOGIN, ""))).passwordLogin);
    }

    @Test
    void excludedDomainsCoverSubdomainsOnly() {
        LoginLinkConfig config = LoginLinkConfig.from(model(Map.of(
                LoginLinkConfig.EXCLUDED_DOMAINS, "Pharmatechnik.de, example.org",
                LoginLinkConfig.EXCLUDED_ROLES, "microsoft-login-only")));
        assertTrue(config.isExcludedDomain("f.jordan@pharmatechnik.de"));
        assertTrue(config.isExcludedDomain("F.Jordan@Mail.Pharmatechnik.DE"));
        assertFalse(config.isExcludedDomain("someone@notpharmatechnik.de"));
        assertFalse(config.isExcludedDomain("someone@autologg.com"));
        assertFalse(config.isExcludedDomain("username-without-at"));
        assertTrue(config.excludedRoles.contains("microsoft-login-only"));
    }

    @Test
    void clampsOutOfRangeAndIgnoresGarbage() {
        LoginLinkConfig config = LoginLinkConfig.from(model(Map.of(
                LoginLinkConfig.LINK_TTL_MINUTES, "500",
                LoginLinkConfig.CODE_LENGTH, "4",
                LoginLinkConfig.MAX_CODE_ATTEMPTS, "x",
                LoginLinkConfig.RESEND_COOLDOWN_SECONDS, "-5",
                LoginLinkConfig.MARK_EMAIL_VERIFIED, "false")));
        assertEquals(60, config.linkTtlMinutes);
        assertEquals(6, config.codeLength);
        assertEquals(5, config.maxCodeAttempts);
        assertEquals(0, config.resendCooldownSeconds);
        assertFalse(config.markEmailVerified);
    }

    @Test
    void parsesExcludedIdentityProviders() {
        LoginLinkConfig config = LoginLinkConfig.from(model(Map.of(
                LoginLinkConfig.EXCLUDED_IDENTITY_PROVIDERS, " entra-acme, Entra-Contoso ;okta ")));
        assertEquals(Set.of("entra-acme", "entra-contoso", "okta"), config.excludedIdentityProviders);
    }

    @Test
    void exposesAllPropertiesInTheAdminConsole() {
        assertEquals(12, LoginLinkConfig.properties().size());
    }

    private static AuthenticatorConfigModel model(Map<String, String> values) {
        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setConfig(new HashMap<>(values));
        return model;
    }
}
