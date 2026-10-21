package at.autologg.keycloak.loginlink;

/** Template attribute {@code loginLinkSent} of the waiting page (login-link-sent.ftl). */
public class LoginLinkSentBean {

    /** Way back offered on the waiting page: to the password, to another address, or none. */
    public static final String BACK_PASSWORD = "password";
    public static final String BACK_ADDRESS = "address";
    public static final String BACK_NONE = "none";

    private final String identifier;
    private final String statusUrl;
    private final int codeLength;
    private final long expiresInSeconds;
    private final long resendAvailableInSeconds;
    private final String back;

    LoginLinkSentBean(String identifier, String statusUrl, int codeLength, long expiresInSeconds,
                      long resendAvailableInSeconds, String back) {
        this.identifier = identifier;
        this.statusUrl = statusUrl;
        this.codeLength = codeLength;
        this.expiresInSeconds = expiresInSeconds;
        this.resendAvailableInSeconds = resendAvailableInSeconds;
        this.back = back;
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

    /** {@link #BACK_PASSWORD}, {@link #BACK_ADDRESS} or {@link #BACK_NONE}. */
    public String getBack() {
        return back;
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
