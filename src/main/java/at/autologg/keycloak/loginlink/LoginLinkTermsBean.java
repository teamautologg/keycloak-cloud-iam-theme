package at.autologg.keycloak.loginlink;

/**
 * Template attribute {@code loginLinkTerms} of login-link-terms.ftl: after the right code for an
 * address without account, the terms are accepted before the account is created.
 */
public class LoginLinkTermsBean {

    private final String address;
    private final boolean termsMissing;
    private final boolean otpOffered;
    private final boolean otpChosen;

    LoginLinkTermsBean(String address, boolean termsMissing, boolean otpOffered, boolean otpChosen) {
        this.address = address;
        this.termsMissing = termsMissing;
        this.otpOffered = otpOffered;
        this.otpChosen = otpChosen;
    }

    /** The address the account is created for, as typed. */
    public String getAddress() {
        return address;
    }

    /** Posted without ticking the box. */
    public boolean isTermsMissing() {
        return termsMissing;
    }

    /** The page offers to protect the new account with an authenticator app (execution config). */
    public boolean isOtpOffered() {
        return otpOffered;
    }

    /** That box was ticked when the page was posted without the terms: it stays ticked. */
    public boolean isOtpChosen() {
        return otpChosen;
    }

    public String getSubmitParameter() {
        return LoginLink.FORM_TERMS;
    }

    public String getTermsParameter() {
        return LoginLink.CONFIRM_TERMS;
    }

    public String getOtpParameter() {
        return LoginLink.CONFIRM_OTP;
    }

    public String getCancelParameter() {
        return LoginLink.FORM_CANCEL;
    }
}
