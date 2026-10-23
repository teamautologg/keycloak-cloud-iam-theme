package at.autologg.keycloak.loginlink;

import java.util.List;

import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.PasswordFormFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;

/** Drop-in replacement for "Password Form" after "Username Form" (identifier-first flows). */
public class LoginLinkPasswordFormFactory extends PasswordFormFactory {

    public static final String PROVIDER_ID = "autologg-password-link";

    @Override
    public Authenticator create(KeycloakSession session) {
        return new LoginLinkPasswordForm(session);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Password Form with Login Link";
    }

    @Override
    public String getHelpText() {
        return "Password, or a sign-in link and code by e-mail (AutoLogg). Users without a password get the e-mail option only.";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return LoginLinkConfig.properties();
    }
}
