package at.autologg.keycloak.loginlink;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.locale.LocaleSelectorProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

/**
 * {@code /realms/{realm}/autologg-login-link}: the page the e-mailed link opens,
 * and the status the waiting login page polls.
 */
public class LoginLinkResource {

    private static final Logger LOG = Logger.getLogger(LoginLinkResource.class);

    private final KeycloakSession session;

    public LoginLinkResource(KeycloakSession session) {
        this.session = session;
    }

    /**
     * State of the caller's own waiting login (found through its auth session
     * cookie): {@code pending}, {@code confirmed}, {@code denied}, {@code expired} or {@code gone}.
     */
    @GET
    @Path("status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@QueryParam("client_id") String clientId, @QueryParam("tab_id") String tabId) {
        RealmModel realm = session.getContext().getRealm();
        String state = "gone";
        ClientModel client = clientId == null ? null : realm.getClientByClientId(clientId);
        if (client != null && tabId != null && !tabId.isBlank()) {
            RootAuthenticationSessionModel root = new AuthenticationSessionManager(session).getCurrentRootAuthenticationSession(realm);
            AuthenticationSessionModel authSession = root == null ? null : root.getAuthenticationSession(client, tabId);
            if (authSession != null && LoginLink.STATE_SENT.equals(authSession.getAuthNote(LoginLink.NOTE_STATE))) {
                if (authSession.getAuthNote(LoginLink.NOTE_DENIED) != null) {
                    state = "denied";
                } else if (session.singleUseObjects().contains(LoginLink.confirmedKey(root.getId(), tabId))) {
                    state = "confirmed";
                } else if (expired(authSession.getAuthNote(LoginLink.NOTE_EXPIRES_AT))) {
                    state = "expired";
                } else {
                    state = "pending";
                }
            }
        }
        return Response.ok("{\"state\":\"" + state + "\"}", MediaType.APPLICATION_JSON_TYPE)
                .header("Cache-Control", "no-store")
                .build();
    }

    /** Shows who asked for the login and when; nothing happens until the recipient confirms. */
    @GET
    @Path("confirm")
    @Produces(MediaType.TEXT_HTML)
    public Response confirmPage(@QueryParam("key") String key) {
        RealmModel realm = session.getContext().getRealm();
        if (!LoginLinkSecrets.isWellFormedToken(key)) {
            return render(LoginLinkConfirmBean.invalid("loginLinkInvalid"), null);
        }
        Map<String, String> link = session.singleUseObjects().get(LoginLink.linkKey(LoginLinkSecrets.sha256(key)));
        if (link == null || !realm.getId().equals(link.get(LoginLink.LINK_REALM))) {
            return render(LoginLinkConfirmBean.invalid("loginLinkInvalid"), null);
        }
        if (expired(link.get(LoginLink.LINK_EXPIRES_AT))) {
            return render(LoginLinkConfirmBean.invalid("loginLinkExpired"), null);
        }
        String register = link.get(LoginLink.LINK_REGISTER);
        UserModel user = register != null ? null : session.users().getUserById(realm, link.get(LoginLink.LINK_USER));
        if (register == null && (user == null || !user.isEnabled())) {
            return render(LoginLinkConfirmBean.invalid("loginLinkInvalid"), null);
        }
        long minutesAgo = Math.max(0, (Time.currentTimeSeconds() - parseLong(link.get(LoginLink.LINK_CREATED_AT))) / 60);
        String address = register != null ? register : user.getEmail();
        return render(LoginLinkConfirmBean.confirm(selfUrl(realm), key, LoginLinkSecrets.maskEmail(address),
                minutesAgo, link.get(LoginLink.LINK_BROWSER), link.get(LoginLink.LINK_OS), choices(link))
                .registering(register != null), user);
    }

    /**
     * Uses up the link. With the number shown on the waiting page the waiting login finishes; a wrong
     * number or "that was not me" ends it, so someone who started a login with another person's
     * address cannot get it confirmed by a careless tap.
     */
    @POST
    @Path("confirm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response confirm(@FormParam(LoginLink.CONFIRM_KEY) String key, @FormParam(LoginLink.CONFIRM_MATCH) String match,
                            @FormParam(LoginLink.CONFIRM_DENY) String deny) {
        RealmModel realm = session.getContext().getRealm();
        if (!LoginLinkSecrets.isWellFormedToken(key)) {
            return render(LoginLinkConfirmBean.invalid("loginLinkInvalid"), null);
        }
        Map<String, String> link = session.singleUseObjects().remove(LoginLink.linkKey(LoginLinkSecrets.sha256(key)));
        if (link == null || !realm.getId().equals(link.get(LoginLink.LINK_REALM))) {
            return render(LoginLinkConfirmBean.invalid("loginLinkInvalid"), null);
        }
        long now = Time.currentTimeSeconds();
        long expiresAt = parseLong(link.get(LoginLink.LINK_EXPIRES_AT));
        if (now > expiresAt) {
            return render(LoginLinkConfirmBean.invalid("loginLinkExpired"), null);
        }
        String register = link.get(LoginLink.LINK_REGISTER);
        UserModel user = register != null ? null : session.users().getUserById(realm, link.get(LoginLink.LINK_USER));
        if (register == null && (user == null || !user.isEnabled())) {
            return render(LoginLinkConfirmBean.invalid("loginLinkInvalid"), null);
        }

        ClientModel client = realm.getClientById(link.get(LoginLink.LINK_CLIENT));
        RootAuthenticationSessionModel root = session.authenticationSessions()
                .getRootAuthenticationSession(realm, link.get(LoginLink.LINK_ROOT_SESSION));
        AuthenticationSessionModel waiting = root == null || client == null
                ? null
                : root.getAuthenticationSession(client, link.get(LoginLink.LINK_TAB));
        boolean sameRequest = register != null
                ? register.equals(waiting == null ? null : waiting.getAuthNote(LoginLink.NOTE_REGISTER))
                : waiting != null && user.getId().equals(waiting.getAuthNote(LoginLink.NOTE_USER));
        if (waiting == null || !LoginLink.STATE_SENT.equals(waiting.getAuthNote(LoginLink.NOTE_STATE)) || !sameRequest) {
            return render(LoginLinkConfirmBean.invalid("loginLinkSessionGone"), user);
        }

        String expected = link.get(LoginLink.LINK_MATCH);
        boolean refused = deny != null;
        if (refused || (expected != null && !expected.equals(match == null ? null : match.trim()))) {
            waiting.setAuthNote(LoginLink.NOTE_DENIED, refused ? "refused" : "wrong number");
            LOG.infof("Login link %s for %s in realm %s", refused ? "refused" : "confirmed with a wrong number",
                    user != null ? "user " + user.getId() : "a new account", realm.getName());
            return render(LoginLinkConfirmBean.denied(refused ? "loginLinkDeniedText" : "loginLinkWrongNumber"), user);
        }

        session.singleUseObjects().put(LoginLink.confirmedKey(root.getId(), link.get(LoginLink.LINK_TAB)),
                Math.max(1, expiresAt - now), register != null
                        ? Map.of(LoginLink.CONFIRMED_USER, "", LoginLink.CONFIRMED_REGISTER, register)
                        : Map.of(LoginLink.CONFIRMED_USER, user.getId()));
        LOG.debugf("Login link confirmed in realm %s", realm.getName());
        String address = register != null ? register : user.getEmail();
        return render(LoginLinkConfirmBean.done(LoginLinkSecrets.maskEmail(address)).registering(register != null), user);
    }

    private Response render(LoginLinkConfirmBean bean, UserModel user) {
        LoginFormsProvider forms = session.getProvider(LoginFormsProvider.class)
                .setAttribute(LoginLink.ATTR_CONFIRM, bean)
                // The URL carries the key: keep it out of caches and Referer headers.
                .setResponseHeader("Cache-Control", "no-store")
                .setResponseHeader("Referrer-Policy", "no-referrer");
        if (user != null) {
            forms.setUser(user);
        }
        return forms.createForm(LoginLink.TEMPLATE_CONFIRM);
    }

    /** This endpoint's URL, keeping the language the e-mail was written in. */
    private String selfUrl(RealmModel realm) {
        UriBuilder self = UriBuilder.fromUri(session.getContext().getUri().getBaseUri())
                .path("realms").path(realm.getName())
                .path(LoginLinkResourceProviderFactory.ID).path("confirm");
        String locale = session.getContext().getUri().getQueryParameters().getFirst(LocaleSelectorProvider.KC_LOCALE_PARAM);
        if (locale != null && locale.matches("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*")) {
            self.queryParam(LocaleSelectorProvider.KC_LOCALE_PARAM, locale);
        }
        return self.build().toString();
    }

    private static List<String> choices(Map<String, String> link) {
        String raw = link.get(LoginLink.LINK_CHOICES);
        return raw == null || raw.isBlank() ? List.of() : Arrays.asList(raw.split(","));
    }

    private static boolean expired(String expiresAt) {
        return expiresAt == null || Time.currentTimeSeconds() > parseLong(expiresAt);
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
