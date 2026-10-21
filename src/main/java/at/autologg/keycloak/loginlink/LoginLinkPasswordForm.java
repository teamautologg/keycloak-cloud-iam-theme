package at.autologg.keycloak.loginlink;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.PasswordForm;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.services.validation.Validation;

/**
 * Keycloak's "Password Form" (second step after "Username Form") plus "send me
 * a login link". Users without a password get the link option only, so the step
 * also works for accounts that never set one.
 */
public class LoginLinkPasswordForm extends PasswordForm {

    private boolean passwordAvailable = true;

    public LoginLinkPasswordForm(KeycloakSession session) {
        super(session);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        passwordAvailable = hasPassword(context.getUser());
        LoginLinkFlow links = links(context);
        if (links.isWaiting()) {
            links.showWaitingPage();
            return;
        }
        if (alreadyAuthenticatedUsingPasswordlessCredential(context)) {
            context.success();
            return;
        }
        if (isConditionalPasskeysEnabled(context.getUser())) {
            webauthnAuth.fillContextForm(context);
        }
        context.challenge(createLoginForm(context.form()));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        passwordAvailable = hasPassword(context.getUser());
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        LoginLinkFlow links = links(context);
        if (form.containsKey(LoginLink.FORM_SEND)) {
            String attempted = context.getAuthenticationSession().getAuthNote(ATTEMPTED_USERNAME);
            links.send(context.getUser(), attempted != null ? attempted : context.getUser().getEmail());
            return;
        }
        if (links.handleWaitingAction(form, () -> authenticate(context))) {
            return;
        }
        if (links.isWaiting()) {
            links.clear();
        }
        super.action(context);
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return super.configuredFor(session, realm, user)
                || (user.getEmail() != null && !user.getEmail().isBlank());
    }

    @Override
    protected Response createLoginForm(LoginFormsProvider form) {
        LoginLinkFlow.offer(form, passwordAvailable);
        return super.createLoginForm(form);
    }

    private LoginLinkFlow links(AuthenticationFlowContext context) {
        return new LoginLinkFlow(this, context, passwordAvailable,
                error -> challenge(context, error, Validation.FIELD_PASSWORD));
    }

    private static boolean hasPassword(UserModel user) {
        return user != null && user.credentialManager().isConfiguredFor(PasswordCredentialModel.TYPE);
    }
}
