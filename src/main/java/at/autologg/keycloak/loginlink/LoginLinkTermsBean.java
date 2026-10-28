package at.autologg.keycloak.loginlink;

/**
 * Template attribute {@code loginLinkTerms} of login-link-terms.ftl: after the right code for an
 * address without account, the terms are accepted before the account is created.
 */
public class LoginLinkTermsBean {

    private final String address;
    private final boolean termsMissing;

    LoginLinkTermsBean(String address, boolean termsMissing) {
        this.address = address;
        this.termsMissing = termsMissing;
    }

    /** The address the account is created for, as typed. */
    public String getAddress() {
        return address;
    }

    /** Posted without ticking the box. */
    public boolean isTermsMissing() {
        return termsMissing;
    }

    public String getSubmitParameter() {
        return LoginLink.FORM_TERMS;
    }

    public String getTermsParameter() {
        return LoginLink.CONFIRM_TERMS;
    }

    public String getCancelParameter() {
        return LoginLink.FORM_CANCEL;
    }
}
