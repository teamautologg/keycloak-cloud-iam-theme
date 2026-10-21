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
 * the password being checked.
 */
public class LoginLinkUsernamePasswordForm extends UsernamePasswordForm {

    public LoginLinkUsernamePasswordForm(KeycloakSession session) {
        super(session);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        LoginLinkFlow links = links(context);
        if (links.isWaiting()) {
            links.showWaitingPage();
            return;
        }
        super.authenticate(context);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        LoginLinkFlow links = links(context);
        if (form.containsKey(LoginLink.FORM_SEND)) {
            sendLink(context, links, form);
            return;
        }
        if (links.handleWaitingAction(form, () -> super.authenticate(context))) {
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
        String username = form.getFirst(AuthenticationManager.FORM_USERNAME);
        if (username == null || username.isBlank()) {
            context.getEvent().error(Errors.USER_NOT_FOUND);
            context.forceChallenge(challenge(context, "loginLinkEnterEmail", Validation.FIELD_USERNAME));
            return;
        }
        AuthenticatorUtils.processRememberMe(context, form);
        links.send(null, username);
    }

    private LoginLinkFlow links(AuthenticationFlowContext context) {
        return new LoginLinkFlow(this, context, true,
                error -> challenge(context, error, Validation.FIELD_USERNAME));
    }

    @Override
    protected Response challenge(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        LoginLinkFlow.offer(context.form(), true);
        return super.challenge(context, formData);
    }

    @Override
    protected Response createLoginForm(LoginFormsProvider form) {
        LoginLinkFlow.offer(form, true);
        return super.createLoginForm(form);
    }
}
