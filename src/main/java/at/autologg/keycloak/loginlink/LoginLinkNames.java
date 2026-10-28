package at.autologg.keycloak.loginlink;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * First and last name for an account created through the login link, taken from the address:
 * thomas.jetzinger@… becomes "Thomas" / "JETZINGER". People correct it in the app, which writes it
 * back to Keycloak. Last names are kept in capital letters everywhere (product rule 02.09.2026).
 */
final class LoginLinkNames {

    record Name(String first, String last) {
    }

    private static final Name FALLBACK = new Name("AutoLogg", "NUTZER");

    private LoginLinkNames() {
    }

    static Name fromEmail(String address) {
        if (address == null || address.indexOf('@') < 1) {
            return FALLBACK;
        }
        String local = address.substring(0, address.lastIndexOf('@'));
        int plus = local.indexOf('+');
        if (plus > 0) {
            local = local.substring(0, plus);
        }
        // Letters only: digits and the special characters an address may hold are no part of a name.
        List<String> parts = Arrays.stream(local.split("[._\\-]+"))
                .map(part -> part.replaceAll("[^\\p{L}']", ""))
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.isEmpty()) {
            return FALLBACK;
        }
        if (parts.size() == 1) {
            return new Name(capitalise(parts.get(0)), parts.get(0).toUpperCase(Locale.ROOT));
        }
        String first = parts.subList(0, parts.size() - 1).stream()
                .map(LoginLinkNames::capitalise)
                .collect(Collectors.joining(" "));
        return new Name(first, parts.get(parts.size() - 1).toUpperCase(Locale.ROOT));
    }

    private static String capitalise(String part) {
        String lower = part.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
