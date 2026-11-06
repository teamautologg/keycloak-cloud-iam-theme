package at.autologg.keycloak.loginlink;

import java.util.List;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.authentication.authenticators.util.AuthenticatorUtils;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;

/**
 * Keycloak's "Username Password Form" plus "send me a login link": the address
 * typed into the username field gets an e-mail with link and code instead of
 * the password being checked. With password login switched off in the execution
 * config the page asks for the address only and every post sends the link.
 *
 * <p>E-mail first (default): the first page asks for the address only, the second
 * offers the link or the password for it. Both pages are this one execution; the
 * address in between is an auth note, and the second page posts it back as the
 * username, so the password check is Keycloak's own.
 *
 * <p>Password option: password login is off, but a text link opens the password
 * field on the same page. The page, the error and the time it takes do not depend
 * on the address, so nothing tells who has an account or a password.
 */
public class LoginLinkUsernamePasswordForm extends UsernamePasswordForm {

    private boolean passwordLogin = true;
    private boolean passwordOption;
    /** Password option: this answer shows the password field. */
    private boolean passwordOpen;
    private boolean emailFirst = true;
    /** E-mail first applies to this request; decided before Keycloak sets the user. */
    private boolean twoPages;
    private AuthenticationFlowContext context;

    public LoginLinkUsernamePasswordForm(KeycloakSession session) {
        super(session);
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        readSettings(context);
        LoginLinkFlow links = links(context);
        links.resumeClosedAppLogin();
        if (links.isWaiting()) {
            links.showWaitingPage();
            return;
        }
        super.authenticate(context);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        readSettings(context);
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        LoginLinkFlow links = links(context);
        passwordOpen = passwordOption && form.containsKey(Validation.FIELD_PASSWORD);
        if (form.containsKey(LoginLink.FORM_SEND)) {
            sendLink(context, links, form);
            return;
        }
        if (links.handleWaitingAction(form, () -> super.authenticate(context))) {
            return;
        }
        if (passwordOption && form.containsKey(LoginLink.FORM_SHOW_PASSWORD)) {
            showPassword(context, form);
            return;
        }
        if (!passwordLogin && !passwordOpen && !form.containsKey("cancel") && !LoginLinkFlow.isPasskeySubmission(form)) {
            // Password login is off: whatever was posted, this form only sends the link.
            sendLink(context, links, form);
            return;
        }
        if (twoPages) {
            if (form.containsKey(LoginLink.FORM_CHANGE_ADDRESS)) {
                changeAddress(context);
                return;
            }
            if (form.containsKey(LoginLink.FORM_NEXT)) {
                takeAddress(context, form);
                return;
            }
        }
        if (links.isWaiting()) {
            links.clear();
        }
        if (passwordOpen) {
            hashForAccountWithoutPassword(context, form);
        }
        super.action(context);
    }

    /** The text link: the same page with the password field open, the address and the box kept. */
    private void showPassword(AuthenticationFlowContext context, MultivaluedMap<String, String> form) {
        passwordOpen = true;
        MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
        for (String field : List.of(AuthenticationManager.FORM_USERNAME, "rememberMe")) {
            String value = form.getFirst(field);
            if (value != null) {
                formData.putSingle(field, value);
            }
        }
        context.forceChallenge(challenge(context, formData));
    }

    /**
     * Keycloak hashes for a wrong password and for an unknown address, but answers an account without a
     * password at once. Without this hash the answer's speed would tell that such an account exists.
     */
    private void hashForAccountWithoutPassword(AuthenticationFlowContext context, MultivaluedMap<String, String> form) {
        String username = form.getFirst(AuthenticationManager.FORM_USERNAME);
        if (username != null && !username.isBlank() && !passwordAvailableFor(username.trim())) {
            AuthenticatorUtils.dummyHash(context);
        }
    }

    /** A locked account gets the usual error: the lock would tell that the account exists. The link still works. */
    @Override
    protected String disabledByBruteForceError(String error) {
        return passwordOption ? Messages.INVALID_USER : super.disabledByBruteForceError(error);
    }

    /** First page posted: remember the address and show the choice. Unknown addresses get the same page. */
    private void takeAddress(AuthenticationFlowContext context, MultivaluedMap<String, String> form) {
        String username = form.getFirst(AuthenticationManager.FORM_USERNAME);
        username = username == null ? "" : username.trim();
        if (username.isEmpty() || AuthenticatorUtils.isUsernameTooLong(username)) {
            context.forceChallenge(challenge(context, "loginLinkEnterEmail", Validation.FIELD_USERNAME));
            return;
        }
        context.getAuthenticationSession().setAuthNote(LoginLink.NOTE_CHOICE_ADDRESS, username);
        context.forceChallenge(challenge(context, new MultivaluedHashMap<>()));
    }

    /** "Andere E-Mail": back to the first page, the address filled in. */
    private void changeAddress(AuthenticationFlowContext context) {
        String address = context.getAuthenticationSession().getAuthNote(LoginLink.NOTE_CHOICE_ADDRESS);
        context.getAuthenticationSession().removeAuthNote(LoginLink.NOTE_CHOICE_ADDRESS);
        MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
        if (address != null) {
            formData.putSingle(AuthenticationManager.FORM_USERNAME, address);
        }
        context.forceChallenge(challenge(context, formData));
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

    private void readSettings(AuthenticationFlowContext context) {
        this.context = context;
        LoginLinkConfig config = LoginLinkConfig.from(context.getAuthenticatorConfig());
        passwordLogin = config.passwordLogin;
        passwordOption = config.passwordOption;
        emailFirst = config.emailFirst;
        twoPages = emailFirstActive(context);
    }

    /** Two pages only while this form has to find out who logs in, and only with password login on. */
    private boolean emailFirstActive(AuthenticationFlowContext context) {
        return emailFirst && passwordLogin && context.getUser() == null
                && !isUserAlreadySetBeforeUsernamePasswordAuth(context) && !usernameHidden(context);
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

    private void offer(LoginFormsProvider form) {
        if (passwordOption) {
            form.setAttribute(LoginLink.ATTR_LOGIN_FORM,
                    new LoginLinkFormBean(passwordOpen, false, LoginLinkFormBean.STEP_SINGLE, null, true));
            return;
        }
        if (context == null || !twoPages) {
            LoginLinkFlow.offer(form, passwordLogin, passwordLogin);
            return;
        }
        String address = context.getAuthenticationSession().getAuthNote(LoginLink.NOTE_CHOICE_ADDRESS);
        if (address == null) {
            form.setAttribute(LoginLink.ATTR_LOGIN_FORM,
                    new LoginLinkFormBean(true, true, LoginLinkFormBean.STEP_EMAIL, null));
        } else {
            form.setAttribute(LoginLink.ATTR_LOGIN_FORM,
                    new LoginLinkFormBean(passwordAvailableFor(address), true, LoginLinkFormBean.STEP_CHOICE, address));
        }
    }

    /** Accounts without a password see no password field; unknown addresses look like accounts with one. */
    private boolean passwordAvailableFor(String address) {
        try {
            UserModel user = KeycloakModelUtils.findUserByNameOrEmail(context.getSession(), context.getRealm(), address);
            return user == null || user.credentialManager().isConfiguredFor(PasswordCredentialModel.TYPE);
        } catch (ModelDuplicateException e) {
            return true;
        }
    }

    @Override
    protected Response challenge(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        offer(context.form());
        return super.challenge(context, formData);
    }

    @Override
    protected Response createLoginForm(LoginFormsProvider form) {
        offer(form);
        return super.createLoginForm(form);
    }
}
