package at.autologg.keycloak.loginlink;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/**
 * Settings of one login-link form execution. Every value has a default, so the
 * execution works without a config; out-of-range values are clamped.
 */
final class LoginLinkConfig {

    static final String LINK_TTL_MINUTES = "linkTtlMinutes";
    static final String CODE_LENGTH = "codeLength";
    static final String MAX_CODE_ATTEMPTS = "maxCodeAttempts";
    static final String RESEND_COOLDOWN_SECONDS = "resendCooldownSeconds";
    static final String MAX_SENDS_PER_HOUR = "maxSendsPerHour";
    static final String EXCLUDED_IDENTITY_PROVIDERS = "excludedIdentityProviders";
    static final String MARK_EMAIL_VERIFIED = "markEmailVerified";
    static final String PASSWORD_LOGIN = "passwordLogin";
    static final String EMAIL_FIRST = "emailFirst";

    static final int DEFAULT_LINK_TTL_MINUTES = 15;
    static final int DEFAULT_CODE_LENGTH = 6;
    static final int DEFAULT_MAX_CODE_ATTEMPTS = 5;
    static final int DEFAULT_RESEND_COOLDOWN_SECONDS = 30;
    static final int DEFAULT_MAX_SENDS_PER_HOUR = 10;

    final int linkTtlMinutes;
    final int codeLength;
    final int maxCodeAttempts;
    final int resendCooldownSeconds;
    final int maxSendsPerHour;
    final Set<String> excludedIdentityProviders;
    final boolean markEmailVerified;
    /** False: the form asks for the address only and never checks a password. */
    final boolean passwordLogin;
    /** Username Password Form: first page asks for the address, the second offers link or password. */
    final boolean emailFirst;

    private LoginLinkConfig(Map<String, String> values) {
        linkTtlMinutes = intValue(values, LINK_TTL_MINUTES, DEFAULT_LINK_TTL_MINUTES, 1, 60);
        codeLength = intValue(values, CODE_LENGTH, DEFAULT_CODE_LENGTH, 6, 10);
        maxCodeAttempts = intValue(values, MAX_CODE_ATTEMPTS, DEFAULT_MAX_CODE_ATTEMPTS, 1, 10);
        resendCooldownSeconds = intValue(values, RESEND_COOLDOWN_SECONDS, DEFAULT_RESEND_COOLDOWN_SECONDS, 0, 600);
        maxSendsPerHour = intValue(values, MAX_SENDS_PER_HOUR, DEFAULT_MAX_SENDS_PER_HOUR, 1, 100);
        excludedIdentityProviders = aliases(values.get(EXCLUDED_IDENTITY_PROVIDERS));
        markEmailVerified = flag(values, MARK_EMAIL_VERIFIED);
        passwordLogin = flag(values, PASSWORD_LOGIN);
        emailFirst = flag(values, EMAIL_FIRST);
    }

    static LoginLinkConfig from(AuthenticatorConfigModel model) {
        Map<String, String> values = model == null || model.getConfig() == null
                ? Collections.emptyMap()
                : model.getConfig();
        return new LoginLinkConfig(values);
    }

    static LoginLinkConfig defaults() {
        return new LoginLinkConfig(Collections.emptyMap());
    }

    static List<ProviderConfigProperty> properties() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(LINK_TTL_MINUTES)
                .label("Link and code lifetime (minutes)")
                .helpText("How long the e-mailed link and code stay valid. Capped by the realm's login timeout. 1-60, default 15.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(String.valueOf(DEFAULT_LINK_TTL_MINUTES))
                .add()
                .property()
                .name(CODE_LENGTH)
                .label("Code length")
                .helpText("Number of digits of the code in the e-mail. 6-10, default 6.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(String.valueOf(DEFAULT_CODE_LENGTH))
                .add()
                .property()
                .name(MAX_CODE_ATTEMPTS)
                .label("Wrong codes per e-mail")
                .helpText("After this many wrong codes the code is void and a new e-mail has to be requested. 1-10, default 5.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(String.valueOf(DEFAULT_MAX_CODE_ATTEMPTS))
                .add()
                .property()
                .name(RESEND_COOLDOWN_SECONDS)
                .label("Resend cooldown (seconds)")
                .helpText("Minimum time between two e-mails for the same address. 0-600, default 30.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(String.valueOf(DEFAULT_RESEND_COOLDOWN_SECONDS))
                .add()
                .property()
                .name(MAX_SENDS_PER_HOUR)
                .label("E-mails per address and hour")
                .helpText("Upper bound of login e-mails per address within one hour. 1-100, default 10.")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue(String.valueOf(DEFAULT_MAX_SENDS_PER_HOUR))
                .add()
                .property()
                .name(EXCLUDED_IDENTITY_PROVIDERS)
                .label("Identity providers without login link")
                .helpText("Comma-separated identity provider aliases. Users linked to one of them get no login e-mail and keep signing in through that provider (e.g. company Entra ID tenants).")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue("")
                .add()
                .property()
                .name(MARK_EMAIL_VERIFIED)
                .label("Mark e-mail as verified")
                .helpText("A successful login through the e-mail proves the address; mark it verified and drop a pending Verify Email action.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .add()
                .property()
                .name(PASSWORD_LOGIN)
                .label("Password login")
                .helpText("Off: the page asks for the e-mail address only and sends the sign-in link; this form accepts no password (passkeys still work). Password grants of the API are a separate flow.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .add()
                .property()
                .name(EMAIL_FIRST)
                .label("E-mail first")
                .helpText("Username Password Form only: the first page asks for the e-mail address, the second offers the sign-in link or the password. Off: address, password and link on one page.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .add()
                .build();
    }

    /** Boolean option that is on unless explicitly set to false. */
    private static boolean flag(Map<String, String> values, String key) {
        String raw = values.get(key);
        return raw == null || raw.isBlank() || Boolean.parseBoolean(raw.trim());
    }

    private static int intValue(Map<String, String> values, String key, int fallback, int min, int max) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Set<String> aliases(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(String::trim)
                .filter(alias -> !alias.isEmpty())
                .map(alias -> alias.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
