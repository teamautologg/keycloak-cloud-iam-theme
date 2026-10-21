package at.autologg.keycloak.loginlink;

import java.util.List;

import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;

/** Drop-in replacement for "Username Password Form" in a browser flow. */
public class LoginLinkUsernamePasswordFormFactory extends UsernamePasswordFormFactory {

    public static final String PROVIDER_ID = "autologg-username-password-link";

    @Override
    public Authenticator create(KeycloakSession session) {
        return new LoginLinkUsernamePasswordForm(session);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Username Password Form with Login Link";
    }

    @Override
    public String getHelpText() {
        return "Username and password, or a sign-in link and code by e-mail (AutoLogg).";
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
