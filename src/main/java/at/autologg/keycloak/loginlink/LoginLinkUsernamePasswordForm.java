package at.autologg.keycloak.loginlink;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.authentication.authenticators.util.AuthenticatorUtils;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.validation.Validation;

/**
 * Keycloak's "Username Password Form" plus "send me a login link": the address
 * typed into the username field gets an e-mail with link and code instead of
 * the password being checked. With password login switched off in the execution
 * config the page asks for the address only and every post sends the link.
 */
public class LoginLinkUsernamePasswordForm extends UsernamePasswordForm {

    private boolean passwordLogin = true;

    public LoginLinkUsernamePasswordForm(KeycloakSession session) {
        super(session);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        passwordLogin = LoginLinkConfig.from(context.getAuthenticatorConfig()).passwordLogin;
        LoginLinkFlow links = links(context);
        if (links.isWaiting()) {
            links.showWaitingPage();
            return;
        }
        super.authenticate(context);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        passwordLogin = LoginLinkConfig.from(context.getAuthenticatorConfig()).passwordLogin;
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        LoginLinkFlow links = links(context);
        if (form.containsKey(LoginLink.FORM_SEND)) {
            sendLink(context, links, form);
            return;
        }
        if (links.handleWaitingAction(form, () -> super.authenticate(context))) {
            return;
        }
        if (!passwordLogin && !form.containsKey("cancel") && !LoginLinkFlow.isPasskeySubmission(form)) {
            // Password login is off: whatever was posted, this form only sends the link.
            sendLink(context, links, form);
            return;
        }
        if (links.isWaiting()) {
            links.clear();
        }
        super.action(context);
    }

    private void sendLink(AuthenticationFlowContext context, LoginLinkFlow links, MultivaluedMap<String, String> form) {
        if (isUserAlreadySetBeforeUsernamePasswordAuth(context) && context.getUser() != null) {
            String attempted = context.getAuthenticationSession().getAuthNote(ATTEMPTED_USERNAME);
            links.send(context.getUser(), attempted != null ? attempted : context.getUser().getEmail());
            return;
        }
        if (usernameHidden(context)) {
            // A step before (Organization Identity-First Login) took the address and found no
            // user; it hides the field, so answer exactly as for a known address.
            links.send(null, context.getAuthenticationSession().getAuthNote(ATTEMPTED_USERNAME));
            return;
        }
        String username = form.getFirst(AuthenticationManager.FORM_USERNAME);
        if (username == null || username.isBlank()) {
            context.getEvent().error(Errors.USER_NOT_FOUND);
            context.forceChallenge(challenge(context, "loginLinkEnterEmail", Validation.FIELD_USERNAME));
            return;
        }
        AuthenticatorUtils.processRememberMe(context, form);
        links.send(null, username);
    }

    private static boolean usernameHidden(AuthenticationFlowContext context) {
        return Boolean.parseBoolean(context.getAuthenticationSession().getAuthNote(USERNAME_HIDDEN))
                && context.getAuthenticationSession().getAuthNote(ATTEMPTED_USERNAME) != null;
    }

    private LoginLinkFlow links(AuthenticationFlowContext context) {
        String back;
        if (passwordLogin) {
            back = LoginLinkSentBean.BACK_PASSWORD;
        } else if (isUserAlreadySetBeforeUsernamePasswordAuth(context) || usernameHidden(context)) {
            back = LoginLinkSentBean.BACK_NONE;
        } else {
            back = LoginLinkSentBean.BACK_ADDRESS;
        }
        return new LoginLinkFlow(this, context, back,
                error -> challenge(context, error, Validation.FIELD_USERNAME));
    }

    @Override
    protected Response challenge(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        LoginLinkFlow.offer(context.form(), passwordLogin, passwordLogin);
        return super.challenge(context, formData);
    }

    @Override
    protected Response createLoginForm(LoginFormsProvider form) {
        LoginLinkFlow.offer(form, passwordLogin, passwordLogin);
        return super.createLoginForm(form);
    }
}
