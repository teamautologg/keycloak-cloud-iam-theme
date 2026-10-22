package at.autologg.keycloak.loginlink;

/** Template attribute {@code loginLink} on the login and password pages: offers the e-mail link. */
public class LoginLinkFormBean {

    /** Address, password and link on one page. */
    public static final String STEP_SINGLE = "single";
    /** E-mail first, page one: the address only. */
    public static final String STEP_EMAIL = "email";
    /** E-mail first, page two: link or password for the address taken before. */
    public static final String STEP_CHOICE = "choice";

    private final boolean passwordAvailable;
    private final boolean passwordLogin;
    private final String step;
    private final String address;

    LoginLinkFormBean(boolean passwordAvailable, boolean passwordLogin) {
        this(passwordAvailable, passwordLogin, STEP_SINGLE, null);
    }

    LoginLinkFormBean(boolean passwordAvailable, boolean passwordLogin, String step, String address) {
        this.passwordAvailable = passwordAvailable;
        this.passwordLogin = passwordLogin;
        this.step = step;
        this.address = address;
    }

    /** Whether the page shows the password field; if not, the link is the only way in. */
    public boolean isPasswordAvailable() {
        return passwordAvailable;
    }

    /** False when password login is switched off for the realm, as opposed to a user without password. */
    public boolean isPasswordLogin() {
        return passwordLogin;
    }

    /** One of {@link #STEP_SINGLE}, {@link #STEP_EMAIL}, {@link #STEP_CHOICE}. */
    public String getStep() {
        return step;
    }

    /** The address of the choice page, as typed. */
    public String getAddress() {
        return address;
    }

    public String getSendParameter() {
        return LoginLink.FORM_SEND;
    }

    public String getNextParameter() {
        return LoginLink.FORM_NEXT;
    }

    public String getChangeAddressParameter() {
        return LoginLink.FORM_CHANGE_ADDRESS;
    }
}
