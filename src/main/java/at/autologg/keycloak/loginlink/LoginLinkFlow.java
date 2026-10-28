package at.autologg.keycloak.loginlink;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.jboss.logging.Logger;
import org.keycloak.WebAuthnConstants;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.authentication.authenticators.util.AuthenticatorUtils;
import org.keycloak.common.util.Time;
import org.keycloak.credential.CredentialModel;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.events.Details;
import org.keycloak.events.EventType;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.light.LightweightUserAdapter;
import org.keycloak.services.validation.Validation;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * The login-link part of the AutoLogg login forms: sending the e-mail, the
 * waiting page and the ways out of it (code, confirmed link, back to the
 * password). Both form authenticators delegate here; one instance serves one
 * request.
 *
 * <p>The e-mail carries a link and a code. The waiting page polls the confirm
 * endpoint's status and finishes the login in the window that started it, so a
 * link opened in the mail app, another browser or on another device still logs
 * in the app's login window. Nothing happens on a plain GET of the link: the
 * recipient has to press "confirm", so mail scanners that open links cannot
 * complete a login someone else started.
 */
final class LoginLinkFlow {

    private static final Logger LOG = Logger.getLogger(LoginLinkFlow.class);

    /** Renders the authenticator's own login page with an error. */
    interface LoginPage {
        Response withError(String messageKey);
    }

    private final AbstractUsernameFormAuthenticator authenticator;
    private final AuthenticationFlowContext context;
    private final LoginLinkConfig config;
    private final String back;
    private final LoginPage loginPage;
    /** This request passed the sending limits: a company domain may be looked up for the mailbox button. */
    private boolean mailboxLookup;

    /**
     * @param back way back the waiting page offers, one of the {@code LoginLinkSentBean.BACK_*} values
     */
    LoginLinkFlow(AbstractUsernameFormAuthenticator authenticator, AuthenticationFlowContext context,
                  String back, LoginPage loginPage) {
        this.authenticator = authenticator;
        this.context = context;
        this.config = LoginLinkConfig.from(context.getAuthenticatorConfig());
        this.back = back;
        this.loginPage = loginPage;
    }

    /**
     * Adds the "send me a link" option to a login page about to be rendered.
     *
     * @param passwordAvailable whether the page shows the password field
     * @param passwordLogin     false when password login is switched off by the execution config
     */
    static void offer(LoginFormsProvider form, boolean passwordAvailable, boolean passwordLogin) {
        form.setAttribute(LoginLink.ATTR_LOGIN_FORM, new LoginLinkFormBean(passwordAvailable, passwordLogin));
    }

    /** Passkey (WebAuthn conditional UI) posts are still Keycloak's to handle. */
    static boolean isPasskeySubmission(MultivaluedMap<String, String> form) {
        return form.containsKey(WebAuthnConstants.AUTHENTICATOR_DATA) || form.containsKey(WebAuthnConstants.ERROR);
    }

    boolean isWaiting() {
        return LoginLink.STATE_SENT.equals(note(LoginLink.NOTE_STATE));
    }

    void showWaitingPage() {
        if (note(LoginLink.NOTE_REGISTER_VERIFIED) != null) {
            context.challenge(termsPage(false));
            return;
        }
        context.challenge(waitingPage(null, null, null));
    }

    /**
     * Handles a post from the waiting page.
     *
     * @return false when the post is not one of ours
     */
    boolean handleWaitingAction(MultivaluedMap<String, String> form, Runnable backToLogin) {
        boolean code = form.containsKey(LoginLink.FORM_CODE);
        boolean check = form.containsKey(LoginLink.FORM_CHECK);
        boolean resend = form.containsKey(LoginLink.FORM_RESEND);
        boolean cancel = form.containsKey(LoginLink.FORM_CANCEL);
        boolean terms = form.containsKey(LoginLink.FORM_TERMS);
        if (!code && !check && !resend && !cancel && !terms) {
            return false;
        }
        if (terms && !cancel && isWaiting()) {
            acceptTermsAndRegister(form);
            return true;
        }
        if (cancel || !isWaiting()) {
            clear();
            backToLogin.run();
        } else if (code) {
            verifyCode(form.getFirst(LoginLink.FORM_CODE));
        } else if (check) {
            checkConfirmed();
        } else {
            resend();
        }
        return true;
    }

    /**
     * Sends link and code and shows the waiting page. The page looks the same
     * whether or not an e-mail went out (unknown address, disabled user, ...),
     * so it does not tell which addresses have an account.
     *
     * @param knownUser  the user when an earlier step identified them, else null
     * @param identifier what the user typed: e-mail address or username
     */
    void send(UserModel knownUser, String identifier) {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        SingleUseObjectProvider store = session.singleUseObjects();
        long now = Time.currentTimeSeconds();
        String typed = identifier == null ? "" : identifier.trim();

        if (config.isExcludedDomain(typed)) {
            // Depends on the domain only, not on an account: tells nothing about who has one.
            context.getEvent().error(Errors.NOT_ALLOWED);
            clear();
            context.challenge(loginPage.withError("loginLinkDomainExcluded"));
            return;
        }

        Long lastSent = longNote(LoginLink.NOTE_SENT_AT);
        if (isWaiting() && lastSent != null && now - lastSent < config.resendCooldownSeconds) {
            context.challenge(waitingPage(null, null, "loginLinkResendTooSoon"));
            return;
        }

        String addressKey = LoginLinkSecrets.addressKey(knownUser != null ? "user:" + knownUser.getId() : "address:" + typed);
        boolean cooledDown = store.putIfAbsent(LoginLink.cooldownKey(realm.getId(), addressKey),
                Math.max(1, config.resendCooldownSeconds));
        if (!cooledDown) {
            throttled(typed, now, "loginLinkResendTooSoon");
            return;
        }
        if (countThisHour(store, LoginLink.hourlyKey(realm.getId(), addressKey, now / 3600)) > config.maxSendsPerHour) {
            throttled(typed, now, "loginLinkTooManyRequests");
            return;
        }
        String ip = publicClientAddress();
        if (ip != null && countThisHour(store, LoginLink.ipKey(realm.getId(), LoginLinkSecrets.addressKey("ip:" + ip), now / 3600))
                > config.maxSendsPerIpPerHour) {
            throttled(typed, now, "loginLinkTooManyRequestsNetwork");
            return;
        }

        mailboxLookup = true;
        UserModel user = knownUser != null ? knownUser : findUser(session, realm, typed);
        String register = user == null ? registrationAddress(realm, typed) : null;
        if (register != null
                && countThisHour(store, LoginLink.registrationsKey(realm.getId(), now / 3600)) > config.maxRegistrationsPerHour) {
            warnRegistrationCap(realm, now / 3600);
            register = null;  // treated like an address that cannot register: same page, no e-mail
        }
        String refusal = register != null ? null : refusal(session, realm, user);
        long ttl = ttlSeconds(realm);
        String code = LoginLinkSecrets.newCode(config.codeLength);

        dropLink(store, authSession);
        authSession.setAuthNote(LoginLink.NOTE_STATE, LoginLink.STATE_SENT);
        authSession.setAuthNote(LoginLink.NOTE_IDENTIFIER, typed);
        authSession.setAuthNote(LoginLink.NOTE_SENT_AT, String.valueOf(now));
        authSession.setAuthNote(LoginLink.NOTE_EXPIRES_AT, String.valueOf(now + ttl));
        authSession.setAuthNote(LoginLink.NOTE_ATTEMPTS, "0");
        authSession.setAuthNote(LoginLink.NOTE_CODE_HASH, LoginLinkSecrets.sha256(code));
        authSession.removeAuthNote(LoginLink.NOTE_TOKEN_HASH);
        authSession.removeAuthNote(LoginLink.NOTE_DENIED);
        authSession.removeAuthNote(LoginLink.NOTE_REGISTER);
        if (knownUser == null && !typed.isEmpty()) {
            // As the password form does: lets brute force protection attribute wrong codes to the account.
            authSession.setAuthNote(AbstractUsernameFormAuthenticator.ATTEMPTED_USERNAME, typed);
        }

        if (refusal != null) {
            authSession.setAuthNote(LoginLink.NOTE_USER, "");
            LOG.debugf("No login link sent in realm %s: %s", realm.getName(), refusal);
            context.challenge(waitingPage(null, null, null));
            return;
        }

        String userAgent = context.getHttpRequest().getHttpHeaders().getHeaderString("User-Agent");
        String browser = UserAgents.browser(userAgent);
        String operatingSystem = UserAgents.operatingSystem(userAgent);
        String token = LoginLinkSecrets.newToken();
        String tokenHash = LoginLinkSecrets.sha256(token);

        Map<String, String> link = new HashMap<>();
        link.put(LoginLink.LINK_REALM, realm.getId());
        link.put(LoginLink.LINK_USER, user == null ? "" : user.getId());
        if (register != null) {
            link.put(LoginLink.LINK_REGISTER, register);
        }
        link.put(LoginLink.LINK_ROOT_SESSION, authSession.getParentSession().getId());
        link.put(LoginLink.LINK_TAB, authSession.getTabId());
        link.put(LoginLink.LINK_CLIENT, authSession.getClient().getId());
        link.put(LoginLink.LINK_CREATED_AT, String.valueOf(now));
        link.put(LoginLink.LINK_EXPIRES_AT, String.valueOf(now + ttl));
        if (browser != null) {
            link.put(LoginLink.LINK_BROWSER, browser);
        }
        if (operatingSystem != null) {
            link.put(LoginLink.LINK_OS, operatingSystem);
        }
        store.put(LoginLink.linkKey(tokenHash), ttl, link);
        authSession.setAuthNote(LoginLink.NOTE_USER, user == null ? "" : user.getId());
        if (register != null) {
            authSession.setAuthNote(LoginLink.NOTE_REGISTER, register);
        }
        authSession.setAuthNote(LoginLink.NOTE_TOKEN_HASH, tokenHash);

        String recipient = user != null ? user.getId() : "new account";
        try {
            sendEmail(session, realm, authSession, user, register, token, code, ttl, browser, operatingSystem);
        } catch (EmailException e) {
            LOG.warnf(e, "Login link e-mail to %s in realm %s failed", recipient, realm.getName());
            clear();
            if (user != null) {
                context.getEvent().user(user);
            }
            context.getEvent().error(Errors.EMAIL_SEND_FAILED);
            context.challenge(loginPage.withError("loginLinkEmailFailed"));
            return;
        }
        LOG.debugf("Login link sent to %s in realm %s", recipient, realm.getName());
        context.challenge(waitingPage(null, null, null));
    }

    void resend() {
        String userId = note(LoginLink.NOTE_USER);
        UserModel user = userId == null || userId.isEmpty()
                ? null
                : context.getSession().users().getUserById(context.getRealm(), userId);
        send(user != null ? user : context.getUser(), note(LoginLink.NOTE_IDENTIFIER));
    }

    void verifyCode(String rawCode) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String code = LoginLinkSecrets.normalizeCode(rawCode);
        if (code.isEmpty()) {
            context.challenge(waitingPage(new FormMessage(LoginLink.FORM_CODE, "loginLinkCodeMissing"), null, null));
            return;
        }
        if (isExpired()) {
            context.challenge(waitingPage(new FormMessage(LoginLink.FORM_CODE, "loginLinkCodeExpired"), null, null));
            return;
        }
        int attempts = intNote(LoginLink.NOTE_ATTEMPTS);
        if (attempts >= config.maxCodeAttempts) {
            context.challenge(waitingPage(new FormMessage(LoginLink.FORM_CODE, "loginLinkCodeLocked"), null, null));
            return;
        }

        UserModel user = waitingUser();
        SingleUseObjectProvider store = context.getSession().singleUseObjects();
        String failuresKey = user == null
                ? null
                : LoginLink.failuresKey(context.getRealm().getId(), user.getId(), Time.currentTimeSeconds() / 3600);
        Map<String, String> failures = failuresKey == null ? null : store.get(failuresKey);
        boolean hourlyLimitReached = failures != null && parse(failures.get("count")) >= LoginLink.MAX_FAILURES_PER_HOUR;

        if (user != null && !hourlyLimitReached
                && LoginLinkSecrets.matchesHash(note(LoginLink.NOTE_CODE_HASH), code)) {
            succeed(user, "code");
            return;
        }
        String register = note(LoginLink.NOTE_REGISTER);
        if (user == null && register != null && LoginLinkSecrets.matchesHash(note(LoginLink.NOTE_CODE_HASH), code)) {
            // The mailbox is proven; the terms come before the account.
            authSession.setAuthNote(LoginLink.NOTE_REGISTER_VERIFIED, "code");
            context.challenge(termsPage(false));
            return;
        }

        authSession.setAuthNote(LoginLink.NOTE_ATTEMPTS, String.valueOf(attempts + 1));
        if (failuresKey != null) {
            countThisHour(store, failuresKey);
            context.getEvent().user(user);
        }
        context.getEvent().error(Errors.INVALID_USER_CREDENTIALS);
        String message = attempts + 1 >= config.maxCodeAttempts ? "loginLinkCodeLocked" : "loginLinkCodeInvalid";
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                waitingPage(new FormMessage(LoginLink.FORM_CODE, message), null, null));
    }

    /** Posted by the waiting page once the status endpoint reports the link as confirmed. */
    void checkConfirmed() {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        Map<String, String> confirmation = context.getSession().singleUseObjects()
                .remove(LoginLink.confirmedKey(authSession.getParentSession().getId(), authSession.getTabId()));
        String register = note(LoginLink.NOTE_REGISTER);
        if (confirmation != null && register != null && register.equals(confirmation.get(LoginLink.CONFIRMED_REGISTER))) {
            // The confirm page took the terms before it confirmed.
            String acceptedAt = confirmation.get(LoginLink.CONFIRMED_TERMS);
            succeedRegistration(register, "link", acceptedAt != null ? acceptedAt : String.valueOf(Time.currentTimeSeconds()));
            return;
        }
        String userId = note(LoginLink.NOTE_USER);
        if (confirmation == null || userId == null || userId.isEmpty()
                || !userId.equals(confirmation.get(LoginLink.CONFIRMED_USER))) {
            context.challenge(waitingPage(null, null, null));
            return;
        }
        UserModel user = waitingUser();
        if (user == null) {
            clear();
            context.challenge(loginPage.withError("loginLinkInvalid"));
            return;
        }
        succeed(user, "link");
    }

    void clear() {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        dropLink(context.getSession().singleUseObjects(), authSession);
        for (String note : List.of(LoginLink.NOTE_STATE, LoginLink.NOTE_USER, LoginLink.NOTE_IDENTIFIER,
                LoginLink.NOTE_CODE_HASH, LoginLink.NOTE_EXPIRES_AT, LoginLink.NOTE_ATTEMPTS,
                LoginLink.NOTE_SENT_AT, LoginLink.NOTE_TOKEN_HASH, LoginLink.NOTE_DENIED,
                LoginLink.NOTE_REGISTER, LoginLink.NOTE_REGISTER_VERIFIED)) {
            authSession.removeAuthNote(note);
        }
    }

    private void succeed(UserModel user, String method) {
        // Disabled meanwhile or locked by brute force protection: renders the login page with the reason.
        if (!authenticator.enabledUser(context, user)) {
            clear();
            return;
        }
        clear();
        if (config.markEmailVerified && !user.isEmailVerified()) {
            removeSetupBeforeVerification(user);
            user.setEmailVerified(true);
            user.removeRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
        }
        // Signing in through the e-mail needs no password; a pending "set your password" (e.g. from the
        // webshop's action mail) would only stand in the way. A password login still asks for it.
        user.removeRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
        context.getEvent().detail(LoginLink.EVENT_DETAIL, method);
        context.setUser(user);
        context.success();
    }

    /**
     * The link of an unknown address was confirmed: creates the account as Keycloak's registration
     * form would (default roles and required actions, REGISTER event), with the address verified and
     * no password, and asks for the name next. If the address got an account in the meantime (e.g.
     * through Google), the link proves the mailbox and logs into that one.
     */
    /** Terms page after the right code: the box ticked creates the account, else the page again. */
    private void acceptTermsAndRegister(MultivaluedMap<String, String> form) {
        String register = note(LoginLink.NOTE_REGISTER);
        if (register == null || note(LoginLink.NOTE_REGISTER_VERIFIED) == null) {
            context.challenge(waitingPage(null, null, null));
            return;
        }
        if (!"on".equals(form.getFirst(LoginLink.CONFIRM_TERMS))) {
            context.challenge(termsPage(true));
            return;
        }
        succeedRegistration(register, note(LoginLink.NOTE_REGISTER_VERIFIED), String.valueOf(Time.currentTimeSeconds()));
    }

    private Response termsPage(boolean termsMissing) {
        LoginFormsProvider form = context.form();
        form.setAttribute(LoginLink.ATTR_TERMS, new LoginLinkTermsBean(note(LoginLink.NOTE_REGISTER), termsMissing));
        return form.createForm(LoginLink.TEMPLATE_TERMS);
    }

    private void succeedRegistration(String address, String method, String termsAcceptedAt) {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        UserModel existing = findUser(session, realm, address);
        if (existing != null) {
            succeed(existing, method);
            return;
        }
        if (!realm.isRegistrationAllowed() || config.isExcludedDomain(address)) {
            clear();
            context.challenge(loginPage.withError("loginLinkInvalid"));
            return;
        }
        UserModel user;
        try {
            // Like the registration form: default roles and default required actions come with it.
            user = session.users().addUser(realm, address);
        } catch (ModelDuplicateException e) {
            // Created a moment ago by a parallel request: that is the account to log into.
            UserModel parallel = findUser(session, realm, address);
            if (parallel == null) {
                throw e;
            }
            succeed(parallel, method);
            return;
        }
        user.setEnabled(true);
        user.setEmail(address);
        user.setEmailVerified(true);
        user.removeRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
        // No name form: taken from the address, corrected in the app, which writes it back to Keycloak.
        LoginLinkNames.Name name = LoginLinkNames.fromEmail(address);
        user.setFirstName(name.first());
        user.setLastName(name.last());
        // Accepted before the account was created: on the confirm page or the terms page.
        user.setSingleAttribute(LoginLink.TERMS_ATTRIBUTE, termsAcceptedAt);
        user.removeRequiredAction(UserModel.RequiredAction.TERMS_AND_CONDITIONS);
        context.getEvent().clone().event(EventType.REGISTER).user(user)
                .detail(Details.USERNAME, address)
                .detail(Details.EMAIL, address)
                .detail(Details.REGISTER_METHOD, LoginLink.REGISTER_METHOD)
                .detail(LoginLink.TERMS_ATTRIBUTE, termsAcceptedAt)
                .success();
        LOG.infof("Login link created user %s in realm %s", user.getId(), realm.getName());
        succeed(user, method);
    }

    /** The address to create an account for, or null: only e-mail addresses, only if the realm lets people register. */
    private String registrationAddress(RealmModel realm, String typed) {
        if (!realm.isRegistrationAllowed() || !Validation.isEmailValid(typed) || config.isExcludedDomain(typed)) {
            return null;
        }
        return typed.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Account pre-hijacking: someone may have registered the address before its owner, with their
     * own password, OTP, passkey or identity provider, waiting for the owner to verify it. The link
     * proves the mailbox for the first time, so nothing set up before survives, and no session either.
     */
    private void removeSetupBeforeVerification(UserModel user) {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        List<CredentialModel> credentials = user.credentialManager().getStoredCredentialsStream().toList();
        credentials.forEach(credential -> user.credentialManager().removeStoredCredentialById(credential.getId()));
        List<String> providers = session.users().getFederatedIdentitiesStream(realm, user)
                .map(FederatedIdentityModel::getIdentityProvider).toList();
        providers.forEach(alias -> session.users().removeFederatedIdentity(realm, user, alias));
        List<UserSessionModel> sessions = session.sessions().getUserSessionsStream(realm, user).toList();
        sessions.forEach(userSession -> AuthenticationManager.backchannelLogout(session, userSession, false));
        List<UserSessionModel> offline = session.sessions().getOfflineUserSessionsStream(realm, user).toList();
        offline.forEach(userSession -> session.sessions().removeOfflineUserSession(realm, userSession));
        if (!credentials.isEmpty() || !providers.isEmpty() || !sessions.isEmpty() || !offline.isEmpty()) {
            String removed = credentials.size() + " credentials, " + providers.size() + " identity provider links, "
                    + (sessions.size() + offline.size()) + " sessions";
            context.getEvent().detail(LoginLink.EVENT_DETAIL_RESET, removed);
            LOG.infof("Login link verified the e-mail of user %s in realm %s for the first time; removed %s",
                    user.getId(), realm.getName(), removed);
        }
    }

    /** First e-mail of this session refused by a limit: show the waiting page with the reason, keep what was sent. */
    private void throttled(String typed, long now, String message) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        if (!isWaiting()) {
            authSession.setAuthNote(LoginLink.NOTE_STATE, LoginLink.STATE_SENT);
            authSession.setAuthNote(LoginLink.NOTE_IDENTIFIER, typed);
            authSession.setAuthNote(LoginLink.NOTE_USER, "");
            authSession.setAuthNote(LoginLink.NOTE_EXPIRES_AT, String.valueOf(now + ttlSeconds(context.getRealm())));
            authSession.setAuthNote(LoginLink.NOTE_ATTEMPTS, "0");
        }
        authSession.setAuthNote(LoginLink.NOTE_SENT_AT, String.valueOf(now));
        context.challenge(waitingPage(null, message, null));
    }

    private Response waitingPage(FormMessage fieldError, String error, String info) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        long now = Time.currentTimeSeconds();
        Long sentAt = longNote(LoginLink.NOTE_SENT_AT);
        Long expiresAt = longNote(LoginLink.NOTE_EXPIRES_AT);
        long resendIn = sentAt == null ? 0 : Math.max(0, sentAt + config.resendCooldownSeconds - now);
        long expiresIn = expiresAt == null ? 0 : Math.max(0, expiresAt - now);
        String typed = note(LoginLink.NOTE_IDENTIFIER);
        String shown = typed != null && typed.contains("@") ? typed : "";

        String statusUrl = UriBuilder.fromUri(context.getUriInfo().getBaseUri())
                .path("realms").path(context.getRealm().getName())
                .path(LoginLinkResourceProviderFactory.ID).path("status")
                .queryParam("client_id", authSession.getClient().getClientId())
                .queryParam("tab_id", authSession.getTabId())
                .build().toString();

        LoginFormsProvider form = context.form();
        form.setAttribute(LoginLink.ATTR_SENT,
                new LoginLinkSentBean(shown, statusUrl, config.codeLength, expiresIn, resendIn, back,
                        note(LoginLink.NOTE_DENIED) != null, mailbox(shown), isDesktop()));
        if (fieldError != null) {
            form.addError(fieldError);
        } else if (error != null) {
            form.setError(error);
        } else if (info != null) {
            form.setInfo(info);
        }
        return form.createForm(LoginLink.TEMPLATE_SENT);
    }

    private void sendEmail(KeycloakSession session, RealmModel realm, AuthenticationSessionModel authSession,
                           UserModel user, String register, String token, String code, long ttl,
                           String browser, String operatingSystem) throws EmailException {
        if (user == null) {
            // Not stored anywhere: only carries the address and the language for the e-mail template.
            user = new LightweightUserAdapter(session, realm, null);
            user.setUsername(register);
            user.setEmail(register);
        }
        UriBuilder link = UriBuilder.fromUri(context.getUriInfo().getBaseUri())
                .path("realms").path(realm.getName())
                .path(LoginLinkResourceProviderFactory.ID).path("confirm")
                .queryParam("key", token);
        if (realm.isInternationalizationEnabled()) {
            Locale locale = session.getContext().resolveLocale(user);
            link.queryParam("kc_locale", locale.toLanguageTag());
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("link", link.build().toString());
        attributes.put("code", LoginLinkSecrets.groupCode(code));
        attributes.put("expirationMinutes", Math.max(1, ttl / 60));
        if (browser != null) {
            attributes.put("browser", browser);
        }
        if (operatingSystem != null) {
            attributes.put("operatingSystem", operatingSystem);
        }
        attributes.put("register", register != null);
        session.getProvider(EmailTemplateProvider.class)
                .setRealm(realm)
                .setUser(user)
                .setAuthenticationSession(authSession)
                .send(register != null ? LoginLink.EMAIL_SUBJECT_REGISTER : LoginLink.EMAIL_SUBJECT, List.of(),
                        LoginLink.EMAIL_TEMPLATE, attributes, user.getEmail());
    }

    private String refusal(KeycloakSession session, RealmModel realm, UserModel user) {
        if (user == null) {
            return "unknown user";
        }
        if (!user.isEnabled()) {
            return "user disabled";
        }
        if (user.getServiceAccountClientLink() != null) {
            return "service account";
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return "user without e-mail";
        }
        if (AuthenticatorUtils.getDisabledByBruteForceEventError(context, user) != null) {
            return "locked by brute force protection";
        }
        for (String roleName : config.excludedRoles) {
            RoleModel role = realm.getRole(roleName);
            if (role != null && user.hasRole(role)) {
                return "user has the excluded role " + roleName;
            }
        }
        if (config.isExcludedDomain(user.getEmail())) {
            return "address of an excluded domain";
        }
        if (!config.excludedIdentityProviders.isEmpty()
                && session.users().getFederatedIdentitiesStream(realm, user)
                .anyMatch(link -> link.getIdentityProvider() != null
                        && config.excludedIdentityProviders.contains(link.getIdentityProvider().toLowerCase(Locale.ROOT)))) {
            return "user signs in through an excluded identity provider";
        }
        return null;
    }

    private static UserModel findUser(KeycloakSession session, RealmModel realm, String typed) {
        if (typed.isEmpty() || AuthenticatorUtils.isUsernameTooLong(typed)) {
            return null;
        }
        try {
            return KeycloakModelUtils.findUserByNameOrEmail(session, realm, typed);
        } catch (ModelDuplicateException e) {
            LOG.warnf("Login link: more than one user for the address typed in realm %s", realm.getName());
            return null;
        }
    }

    private UserModel waitingUser() {
        String userId = note(LoginLink.NOTE_USER);
        return userId == null || userId.isEmpty()
                ? null
                : context.getSession().users().getUserById(context.getRealm(), userId);
    }

    /**
     * "Open Gmail" on desktops only: on phones the login runs in the app's in-app browser, where a
     * link would leave the waiting page, and the mail app is a tap away anyway.
     */
    private MailProviders.MailProvider mailbox(String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        String os = UserAgents.operatingSystem(context.getHttpRequest().getHttpHeaders().getHeaderString("User-Agent"));
        if ("iPhone".equals(os) || "iPad".equals(os) || "Android".equals(os)) {
            return null;
        }
        Map<String, String> smtp = context.getRealm().getSmtpConfig();
        return MailProviders.forAddress(address, smtp == null ? null : smtp.get("from"), mailboxLookup).orElse(null);
    }

    private static volatile long registrationCapWarnedHour = -1;

    /** Once per hour: someone may be mass-mailing made-up addresses through the registration. */
    private static void warnRegistrationCap(RealmModel realm, long hour) {
        if (registrationCapWarnedHour != hour) {
            registrationCapWarnedHour = hour;
            LOG.warnf("Login link: more registration e-mails than maxRegistrationsPerHour in realm %s this hour; "
                    + "unknown addresses get no e-mail until the next hour", realm.getName());
        }
    }

    /** Windows or macOS, where a mail app can be opened from the page; phones log in in the app's browser. */
    private boolean isDesktop() {
        String os = UserAgents.operatingSystem(context.getHttpRequest().getHttpHeaders().getHeaderString("User-Agent"));
        return "Windows".equals(os) || "macOS".equals(os);
    }

    /** Expired, or the recipient refused it on the confirm page. */
    private boolean isExpired() {
        Long expiresAt = longNote(LoginLink.NOTE_EXPIRES_AT);
        return expiresAt == null || Time.currentTimeSeconds() > expiresAt || note(LoginLink.NOTE_DENIED) != null;
    }

    /**
     * The client's address when it is a public one. Behind a proxy that does not forward the
     * client address Keycloak sees the proxy's private address; limiting that would limit everyone.
     */
    private String publicClientAddress() {
        String address = context.getConnection() == null ? null : context.getConnection().getRemoteAddr();
        if (address == null || !address.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            InetAddress inet = InetAddress.getByName(address);
            byte[] raw = inet.getAddress();
            boolean uniqueLocalV6 = raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
            if (inet.isLoopbackAddress() || inet.isSiteLocalAddress() || inet.isLinkLocalAddress()
                    || inet.isAnyLocalAddress() || uniqueLocalV6) {
                if (!PRIVATE_ADDRESS_LOGGED) {
                    PRIVATE_ADDRESS_LOGGED = true;
                    LOG.infof("Login link: client address %s is private (proxy without forwarded headers?); "
                            + "the per-IP limit does not apply to it", address);
                }
                return null;
            }
            return inet.getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Link and code of the login TTL, capped by the realm's login timeout so the waiting session outlives them. */
    private long ttlSeconds(RealmModel realm) {
        long ttl = config.linkTtlMinutes * 60L;
        int loginTimeout = realm.getAccessCodeLifespanLogin();
        if (loginTimeout > 0) {
            ttl = Math.min(ttl, loginTimeout);
        }
        return Math.max(60, ttl);
    }

    private static volatile boolean PRIVATE_ADDRESS_LOGGED;

    private static void dropLink(SingleUseObjectProvider store, AuthenticationSessionModel authSession) {
        String tokenHash = authSession.getAuthNote(LoginLink.NOTE_TOKEN_HASH);
        if (tokenHash != null && !tokenHash.isEmpty()) {
            store.remove(LoginLink.linkKey(tokenHash));
        }
        store.remove(LoginLink.confirmedKey(authSession.getParentSession().getId(), authSession.getTabId()));
    }

    /** Counter in the single-use store that lives for one hour; returns the value after counting. */
    private static int countThisHour(SingleUseObjectProvider store, String key) {
        Map<String, String> entry = store.get(key);
        int count = (entry == null ? 0 : parse(entry.get("count"))) + 1;
        store.put(key, 3600, Map.of("count", String.valueOf(count)));
        return count;
    }

    private String note(String name) {
        return context.getAuthenticationSession().getAuthNote(name);
    }

    private Long longNote(String name) {
        String value = note(name);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intNote(String name) {
        Long value = longNote(name);
        return value == null ? 0 : value.intValue();
    }

    private static int parse(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
