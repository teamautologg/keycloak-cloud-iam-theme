package at.autologg.keycloak.loginlink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoginLinkNamesTest {

    private static void assertName(String address, String first, String last) {
        LoginLinkNames.Name name = LoginLinkNames.fromEmail(address);
        assertEquals(first, name.first(), address);
        assertEquals(last, name.last(), address);
    }

    @Test
    void firstAndLastNameFromTheAddress() {
        assertName("thomas.jetzinger@autologg.com", "Thomas", "JETZINGER");
        assertName("Thomas.Jetzinger+test1@autologg.com", "Thomas", "JETZINGER");
        assertName("anna-maria_schmidt@example.org", "Anna Maria", "SCHMIDT");
        assertName("j.doe2@example.org", "J", "DOE");
        assertName("jürgen.müller@beispiel.at", "Jürgen", "MÜLLER");
    }

    @Test
    void oneWordGoesIntoBothFields() {
        assertName("max@gmail.com", "Max", "MAX");
        assertName("office2024@firma.at", "Office", "OFFICE");
    }

    @Test
    void nothingUsableFallsBack() {
        assertName("12345@firma.at", "AutoLogg", "NUTZER");
        assertName("no-at-sign", "AutoLogg", "NUTZER");
    }
}
