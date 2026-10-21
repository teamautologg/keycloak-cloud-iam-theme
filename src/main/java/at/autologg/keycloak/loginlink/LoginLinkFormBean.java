package at.autologg.keycloak.loginlink;

/** Template attribute {@code loginLink} on the login and password pages: offers the e-mail link. */
public class LoginLinkFormBean {

    private final boolean passwordAvailable;

    LoginLinkFormBean(boolean passwordAvailable) {
        this.passwordAvailable = passwordAvailable;
    }

    /** False when the user is known and has no password; the page then offers the link only. */
    public boolean isPasswordAvailable() {
        return passwordAvailable;
    }

    public String getSendParameter() {
        return LoginLink.FORM_SEND;
    }
}
