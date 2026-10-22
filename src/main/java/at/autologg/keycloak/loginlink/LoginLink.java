package at.autologg.keycloak.loginlink;

/**
 * Names shared by the login forms, the confirm endpoint and the templates.
 *
 * <p>A request lives in three places: auth notes on the authentication session
 * that waits for it (code hash, expiry, attempts), a single-use entry per e-mailed
 * link that points back to that session, and a single-use "confirmed" marker the
 * confirm endpoint leaves for the waiting session. The single-use store is
 * cluster-wide, so the link may be confirmed on another node, browser or device.
 */
final class LoginLink {

    // Auth notes of the waiting authentication session.
    static final String NOTE_STATE = "autologg.loginLink.state";
    static final String STATE_SENT = "sent";
    static final String NOTE_USER = "autologg.loginLink.user";
    static final String NOTE_IDENTIFIER = "autologg.loginLink.identifier";
    static final String NOTE_CODE_HASH = "autologg.loginLink.code";
    static final String NOTE_EXPIRES_AT = "autologg.loginLink.expiresAt";
    static final String NOTE_ATTEMPTS = "autologg.loginLink.attempts";
    static final String NOTE_SENT_AT = "autologg.loginLink.sentAt";
    static final String NOTE_TOKEN_HASH = "autologg.loginLink.token";
    /** Address taken on the first page of the e-mail-first login; set while the second page is shown. */
    static final String NOTE_CHOICE_ADDRESS = "autologg.loginLink.address";

    // Single-use store.
    static final String LINK_PREFIX = "autologg-login-link:";
    static final String CONFIRMED_PREFIX = "autologg-login-link-confirmed:";
    static final String COOLDOWN_PREFIX = "autologg-login-link-cooldown:";
    static final String HOURLY_PREFIX = "autologg-login-link-hourly:";
    static final String FAILURES_PREFIX = "autologg-login-link-failures:";

    // Entries of a link.
    static final String LINK_REALM = "realm";
    static final String LINK_USER = "user";
    static final String LINK_ROOT_SESSION = "root";
    static final String LINK_TAB = "tab";
    static final String LINK_CLIENT = "client";
    static final String LINK_CREATED_AT = "created";
    static final String LINK_EXPIRES_AT = "expires";
    static final String LINK_BROWSER = "browser";
    static final String LINK_OS = "os";
    static final String CONFIRMED_USER = "user";

    // Form parameters of the login and waiting pages.
    static final String FORM_SEND = "loginLink";
    static final String FORM_CODE = "loginLinkCode";
    static final String FORM_CHECK = "loginLinkCheck";
    static final String FORM_RESEND = "loginLinkResend";
    static final String FORM_CANCEL = "loginLinkCancel";
    static final String FORM_NEXT = "loginLinkNext";
    static final String FORM_CHANGE_ADDRESS = "loginLinkChangeAddress";

    // Templates and their attributes.
    static final String TEMPLATE_SENT = "login-link-sent.ftl";
    static final String TEMPLATE_CONFIRM = "login-link-confirm.ftl";
    static final String EMAIL_TEMPLATE = "login-link.ftl";
    static final String EMAIL_SUBJECT = "loginLinkEmailSubject";
    static final String ATTR_LOGIN_FORM = "loginLink";
    static final String ATTR_SENT = "loginLinkSent";
    static final String ATTR_CONFIRM = "loginLinkConfirm";

    /** Event detail on the LOGIN event: "link" or "code". */
    static final String EVENT_DETAIL = "login_link";

    /** Wrong codes per user and hour across all sessions before codes stop working for that hour. */
    static final int MAX_FAILURES_PER_HOUR = 20;

    private LoginLink() {
    }

    static String linkKey(String tokenHash) {
        return LINK_PREFIX + tokenHash;
    }

    static String confirmedKey(String rootSessionId, String tabId) {
        return CONFIRMED_PREFIX + rootSessionId + ":" + tabId;
    }

    static String cooldownKey(String realmId, String addressKey) {
        return COOLDOWN_PREFIX + realmId + ":" + addressKey;
    }

    static String hourlyKey(String realmId, String addressKey, long hour) {
        return HOURLY_PREFIX + realmId + ":" + addressKey + ":" + hour;
    }

    static String failuresKey(String realmId, String userId, long hour) {
        return FAILURES_PREFIX + realmId + ":" + userId + ":" + hour;
    }
}
