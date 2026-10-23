package at.autologg.keycloak.loginlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoginLinkSecretsTest {

    @Test
    void tokensAreUrlSafeAndUnique() {
        String first = LoginLinkSecrets.newToken();
        String second = LoginLinkSecrets.newToken();
        assertEquals(43, first.length());
        assertTrue(LoginLinkSecrets.isWellFormedToken(first));
        assertNotEquals(first, second);
    }

    @Test
    void rejectsMalformedTokens() {
        assertFalse(LoginLinkSecrets.isWellFormedToken(null));
        assertFalse(LoginLinkSecrets.isWellFormedToken("short"));
        assertFalse(LoginLinkSecrets.isWellFormedToken("a".repeat(40) + "/../"));
        assertFalse(LoginLinkSecrets.isWellFormedToken("a".repeat(129)));
    }

    @Test
    void codesHaveTheRequestedNumberOfDigits() {
        for (int length = 6; length <= 10; length++) {
            String code = LoginLinkSecrets.newCode(length);
            assertEquals(length, code.length());
            assertTrue(code.chars().allMatch(Character::isDigit), code);
        }
    }

    @Test
    void typedCodesAreNormalizedToDigits() {
        assertEquals("123456", LoginLinkSecrets.normalizeCode(" 123 456 "));
        assertEquals("123456", LoginLinkSecrets.normalizeCode("123-456"));
        assertEquals("", LoginLinkSecrets.normalizeCode(null));
        assertEquals("", LoginLinkSecrets.normalizeCode("abc"));
    }

    @Test
    void hashComparison() {
        String hash = LoginLinkSecrets.sha256("123456");
        assertTrue(LoginLinkSecrets.matchesHash(hash, "123456"));
        assertFalse(LoginLinkSecrets.matchesHash(hash, "123457"));
        assertFalse(LoginLinkSecrets.matchesHash(hash, ""));
        assertFalse(LoginLinkSecrets.matchesHash(null, "123456"));
        assertFalse(LoginLinkSecrets.matchesHash("", ""));
    }

    @Test
    void addressKeyIgnoresCaseAndSurroundingSpace() {
        assertEquals(LoginLinkSecrets.addressKey("Thomas@AutoLogg.com "), LoginLinkSecrets.addressKey("thomas@autologg.com"));
        assertNotEquals(LoginLinkSecrets.addressKey("a@autologg.com"), LoginLinkSecrets.addressKey("b@autologg.com"));
    }

    @Test
    void groupsCodesInThrees() {
        assertEquals("123 456", LoginLinkSecrets.groupCode("123456"));
        assertEquals("1 234 567", LoginLinkSecrets.groupCode("1234567"));
        assertEquals("12 345 678", LoginLinkSecrets.groupCode("12345678"));
    }

    @Test
    void masksEmailAddresses() {
        assertEquals("t•••••@autologg.com", LoginLinkSecrets.maskEmail("thomas@autologg.com"));
        assertEquals("a•••@b.de", LoginLinkSecrets.maskEmail("a@b.de"));
        assertEquals("ma•••••••••••@example.org", LoginLinkSecrets.maskEmail("maxmustermann@example.org"));
        assertEquals("", LoginLinkSecrets.maskEmail(null));
    }

    @Test
    void matchChoicesHoldTheNumberAndTwoDifferentDecoys() {
        for (int i = 0; i < 200; i++) {
            String number = LoginLinkSecrets.newMatchNumber();
            assertTrue(number.matches("[1-9][0-9]"), number);
            java.util.List<String> choices = LoginLinkSecrets.matchChoices(number);
            assertEquals(3, choices.size());
            assertEquals(3, new java.util.HashSet<>(choices).size());
            assertTrue(choices.contains(number));
            assertTrue(choices.stream().allMatch(c -> c.matches("[1-9][0-9]")));
        }
    }
}
