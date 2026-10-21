package at.autologg.keycloak.loginlink;

/** Template attribute {@code loginLinkConfirm} of the page the e-mailed link opens (login-link-confirm.ftl). */
public class LoginLinkConfirmBean {

    /** Asks for the confirming click. */
    public static final String STATE_CONFIRM = "confirm";
    /** The waiting login continues. */
    public static final String STATE_DONE = "done";
    /** Unknown, used or expired link, or the login it belongs to is gone. */
    public static final String STATE_INVALID = "invalid";

    private final String state;
    private final String actionUrl;
    private final String key;
    private final String email;
    private final long minutesAgo;
    private final String browser;
    private final String operatingSystem;
    private final String reason;

    private LoginLinkConfirmBean(String state, String actionUrl, String key, String email, long minutesAgo,
                                 String browser, String operatingSystem, String reason) {
        this.state = state;
        this.actionUrl = actionUrl;
        this.key = key;
        this.email = email;
        this.minutesAgo = minutesAgo;
        this.browser = browser;
        this.operatingSystem = operatingSystem;
        this.reason = reason;
    }

    static LoginLinkConfirmBean confirm(String actionUrl, String key, String email, long minutesAgo,
                                        String browser, String operatingSystem) {
        return new LoginLinkConfirmBean(STATE_CONFIRM, actionUrl, key, email, minutesAgo, browser, operatingSystem, null);
    }

    static LoginLinkConfirmBean done(String email) {
        return new LoginLinkConfirmBean(STATE_DONE, null, null, email, 0, null, null, null);
    }

    /** {@code reason} is a message key explaining why the link does not work. */
    static LoginLinkConfirmBean invalid(String reason) {
        return new LoginLinkConfirmBean(STATE_INVALID, null, null, null, 0, null, null, reason);
    }

    public String getState() {
        return state;
    }

    public String getActionUrl() {
        return actionUrl;
    }

    public String getKey() {
        return key;
    }

    /** Masked address the link was sent to. */
    public String getEmail() {
        return email;
    }

    public long getMinutesAgo() {
        return minutesAgo;
    }

    public String getBrowser() {
        return browser;
    }

    public String getOperatingSystem() {
        return operatingSystem;
    }

    public String getReason() {
        return reason;
    }
}
