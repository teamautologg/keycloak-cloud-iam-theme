package at.autologg.keycloak.loginlink;

import java.net.URI;
import java.util.Locale;

import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.endpoints.AuthorizationEndpoint;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Template attribute {@code website} on our pages when the login was started by the website
 * (client {@value #CLIENT_ID}): the logo and the legal links lead to the website the visitor came
 * from, and a visitor in the middle of an order is told so and can go back to the checkout.
 * Logins of every other client do not get the attribute, so their pages stay as they are.
 */
public class LoginLinkWebsiteBean {

    /** The website's client, the same id in the realms {@code autologg} and {@code staging-autologg}. */
    static final String CLIENT_ID = "autologg-website-v2";

    /**
     * Request parameter the website may send with the authorization request: {@code order} for a
     * login out of the checkout, anything else for a login that is not part of an order.
     */
    static final String FLOW_PARAMETER = "al_flow";
    static final String FLOW_ORDER = "order";

    /** Where "back to the order" leads, below the website's origin. */
    static final String CHECKOUT_PATH = "/de/shop/fahrtenbuch-lizenz/#kasse";

    private final String origin;
    private final boolean order;

    LoginLinkWebsiteBean(String origin, boolean order) {
        this.origin = origin;
        this.order = order;
    }

    /** Adds the attribute to a page about to be rendered, for logins of the website only. */
    static void offer(LoginFormsProvider form, AuthenticationSessionModel authSession) {
        if (form == null || authSession == null || authSession.getClient() == null) {
            return;
        }
        LoginLinkWebsiteBean bean = from(authSession.getClient().getClientId(), authSession.getRedirectUri(),
                authSession.getClientNote(AuthorizationEndpoint.LOGIN_SESSION_NOTE_ADDITIONAL_REQ_PARAMS_PREFIX + FLOW_PARAMETER),
                authSession.getClientNote(OIDCLoginProtocol.LOGIN_HINT_PARAM));
        if (bean != null) {
            form.setAttribute(LoginLink.ATTR_WEBSITE, bean);
        }
    }

    /**
     * @param flow      the website's {@value #FLOW_PARAMETER} parameter, null if it sent none
     * @param loginHint the address the website passed along; the checkout asks for it before the
     *                  login, so without {@code flow} a hint stands for a login out of the checkout
     * @return null for other clients and for redirect addresses that are no web address
     */
    static LoginLinkWebsiteBean from(String clientId, String redirectUri, String flow, String loginHint) {
        if (!CLIENT_ID.equals(clientId)) {
            return null;
        }
        String origin = originOf(redirectUri);
        if (origin == null) {
            return null;
        }
        boolean order = flow != null ? FLOW_ORDER.equals(flow.trim()) : loginHint != null && !loginHint.isBlank();
        return new LoginLinkWebsiteBean(origin, order);
    }

    /** Scheme, host and port of an http(s) address, e.g. {@code https://www.autologg.com}. */
    static String originOf(String address) {
        if (address == null) {
            return null;
        }
        try {
            URI uri = new URI(address.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || !(scheme.equals("https") || scheme.equals("http"))) {
                return null;
            }
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (Exception e) {
            return null;
        }
    }

    /** The website the login returns to, without a trailing slash. */
    public String getOrigin() {
        return origin;
    }

    /** Whether the visitor logs in out of the checkout. */
    public boolean isOrder() {
        return order;
    }

    /** The checkout of the website the login came from. */
    public String getCheckoutUrl() {
        return origin + CHECKOUT_PATH;
    }
}
