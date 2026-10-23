package at.autologg.keycloak.loginlink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Random link tokens and codes, and the hashes they are stored under. */
final class LoginLinkSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,128}");

    private LoginLinkSecrets() {
    }

    /** 256 random bits, URL-safe. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String newCode(int length) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }

    /** Two-digit number for matching the waiting page with the confirm page. */
    static String newMatchNumber() {
        return String.valueOf(10 + RANDOM.nextInt(90));
    }

    /** The number and two different two-digit decoys, shuffled. */
    static List<String> matchChoices(String number) {
        List<String> choices = new ArrayList<>(List.of(number));
        while (choices.size() < 3) {
            String decoy = newMatchNumber();
            if (!choices.contains(decoy)) {
                choices.add(decoy);
            }
        }
        Collections.shuffle(choices, RANDOM);
        return choices;
    }

    static boolean isWellFormedToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** Keeps the digits only, so "123 456" and "123-456" work as typed from the e-mail. */
    static String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        raw.codePoints()
                .filter(c -> c >= '0' && c <= '9')
                .limit(32)
                .forEach(digits::appendCodePoint);
        return digits.toString();
    }

    static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Constant-time comparison of a stored hash with the hash of the candidate. */
    static boolean matchesHash(String expectedHash, String candidate) {
        if (expectedHash == null || expectedHash.isEmpty() || candidate == null || candidate.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                sha256(candidate).getBytes(StandardCharsets.US_ASCII));
    }

    /** Key for per-address limits: case-insensitive, never the address itself. */
    static String addressKey(String identifier) {
        return sha256(identifier == null ? "" : identifier.trim().toLowerCase(Locale.ROOT));
    }

    /** "123456" -> "123 456"; codes of other lengths are grouped in threes as well. */
    static String groupCode(String code) {
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && (code.length() - i) % 3 == 0) {
                grouped.append(' ');
            }
            grouped.append(code.charAt(i));
        }
        return grouped.toString();
    }

    /** "thomas@autologg.com" -> "t•••••@autologg.com". */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email.charAt(0) + "•••";
        }
        String local = email.substring(0, at);
        int visible = local.length() > 6 ? 2 : 1;
        return local.substring(0, visible) + "•".repeat(Math.max(3, local.length() - visible)) + email.substring(at);
    }
}
