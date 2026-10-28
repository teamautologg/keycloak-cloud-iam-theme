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
 * a login link". Users without a password - and everybody when password login is
 * switched off in the execution config - get the e-mail option only, so the step
 * also works for accounts that never set one.
 */
public class LoginLinkPasswordForm extends PasswordForm {

    private boolean passwordLogin = true;
    private boolean passwordAvailable = true;

    public LoginLinkPasswordForm(KeycloakSession session) {
        super(session);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        readSettings(context);
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
        readSettings(context);
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        LoginLinkFlow links = links(context);
        if (form.containsKey(LoginLink.FORM_SEND)) {
            sendLink(context, links);
            return;
        }
        if (links.handleWaitingAction(form, () -> authenticate(context))) {
            return;
        }
        if (!passwordAvailable && !form.containsKey("cancel") && !LoginLinkFlow.isPasskeySubmission(form)) {
            // The page offers nothing but the link, so whatever was posted sends it; no password is checked.
            sendLink(context, links);
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
        LoginLinkFlow.offer(form, passwordAvailable, passwordLogin);
        return super.createLoginForm(form);
    }

    private void sendLink(AuthenticationFlowContext context, LoginLinkFlow links) {
        String attempted = context.getAuthenticationSession().getAuthNote(ATTEMPTED_USERNAME);
        links.send(context.getUser(), attempted != null ? attempted : context.getUser().getEmail());
    }

    private void readSettings(AuthenticationFlowContext context) {
        passwordLogin = LoginLinkConfig.from(context.getAuthenticatorConfig()).passwordLogin;
        passwordAvailable = passwordLogin && hasPassword(context.getUser());
    }

    private LoginLinkFlow links(AuthenticationFlowContext context) {
        // Without a password field the waiting page offers no way back; the username box on top restarts the login.
        String back = passwordAvailable ? LoginLinkSentBean.BACK_PASSWORD : LoginLinkSentBean.BACK_NONE;
        return new LoginLinkFlow(this, context, back,
                error -> challenge(context, error, Validation.FIELD_PASSWORD));
    }

    private static boolean hasPassword(UserModel user) {
        return user != null && user.credentialManager().isConfiguredFor(PasswordCredentialModel.TYPE);
    }
}
