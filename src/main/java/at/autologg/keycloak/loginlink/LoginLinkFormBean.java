package at.autologg.keycloak.loginlink;

/** Template attribute {@code loginLink} on the login and password pages: offers the e-mail link. */
public class LoginLinkFormBean {

    private final boolean passwordAvailable;
    private final boolean passwordLogin;

    LoginLinkFormBean(boolean passwordAvailable, boolean passwordLogin) {
        this.passwordAvailable = passwordAvailable;
        this.passwordLogin = passwordLogin;
    }

    /** Whether the page shows the password field; if not, the link is the only way in. */
    public boolean isPasswordAvailable() {
        return passwordAvailable;
    }

    /** False when password login is switched off for the realm, as opposed to a user without password. */
    public boolean isPasswordLogin() {
        return passwordLogin;
    }

    public String getSendParameter() {
        return LoginLink.FORM_SEND;
    }
}
