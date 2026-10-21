package at.autologg.keycloak.loginlink;

/** Template attribute {@code loginLinkSent} of the waiting page (login-link-sent.ftl). */
public class LoginLinkSentBean {

    private final String identifier;
    private final String statusUrl;
    private final int codeLength;
    private final long expiresInSeconds;
    private final long resendAvailableInSeconds;
    private final boolean passwordFallback;

    LoginLinkSentBean(String identifier, String statusUrl, int codeLength, long expiresInSeconds,
                      long resendAvailableInSeconds, boolean passwordFallback) {
        this.identifier = identifier;
        this.statusUrl = statusUrl;
        this.codeLength = codeLength;
        this.expiresInSeconds = expiresInSeconds;
        this.resendAvailableInSeconds = resendAvailableInSeconds;
        this.passwordFallback = passwordFallback;
    }

    /** The address as the user typed it; empty when they signed in with a username. */
    public String getIdentifier() {
        return identifier;
    }

    /** Polled by the page to learn that the link was confirmed, possibly on another device. */
    public String getStatusUrl() {
        return statusUrl;
    }

    public int getCodeLength() {
        return codeLength;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public long getResendAvailableInSeconds() {
        return resendAvailableInSeconds;
    }

    /** Whether the page offers to go back to the password. */
    public boolean isPasswordFallback() {
        return passwordFallback;
    }

    public String getCodeParameter() {
        return LoginLink.FORM_CODE;
    }

    public String getCheckParameter() {
        return LoginLink.FORM_CHECK;
    }

    public String getResendParameter() {
        return LoginLink.FORM_RESEND;
    }

    public String getCancelParameter() {
        return LoginLink.FORM_CANCEL;
    }
}
