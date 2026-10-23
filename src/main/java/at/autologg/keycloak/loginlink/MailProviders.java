package at.autologg.keycloak.loginlink;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.jboss.logging.Logger;

/**
 * Web mailbox for an address, for the "open Gmail" button of the waiting page: known
 * consumer domains directly, company domains by their MX records (Microsoft 365, Google
 * Workspace). Depends on the domain only, never on whether an account exists.
 */
final class MailProviders {

    private static final Logger LOG = Logger.getLogger(MailProviders.class);

    /** Web mailbox to open: display name and URL. */
    record MailProvider(String name, String url) {
    }

    static final String GMAIL = "Gmail";
    static final String OUTLOOK = "Outlook";

    private static final String GMAIL_URL = "https://mail.google.com/mail/u/0/";
    private static final String OUTLOOK_LIVE_URL = "https://outlook.live.com/mail/0/";
    private static final String OUTLOOK_365_URL = "https://outlook.office.com/mail/";

    private static final Map<String, MailProvider> CONSUMER = Map.ofEntries(
            Map.entry("gmail.com", new MailProvider(GMAIL, GMAIL_URL)),
            Map.entry("googlemail.com", new MailProvider(GMAIL, GMAIL_URL)),
            Map.entry("outlook.com", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("outlook.de", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("outlook.at", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("hotmail.com", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("hotmail.de", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("hotmail.at", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("live.com", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("live.de", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("live.at", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("msn.com", new MailProvider(OUTLOOK, OUTLOOK_LIVE_URL)),
            Map.entry("yahoo.com", new MailProvider("Yahoo Mail", "https://mail.yahoo.com/")),
            Map.entry("yahoo.de", new MailProvider("Yahoo Mail", "https://mail.yahoo.com/")),
            Map.entry("ymail.com", new MailProvider("Yahoo Mail", "https://mail.yahoo.com/")),
            Map.entry("gmx.at", new MailProvider("GMX", "https://www.gmx.at/")),
            Map.entry("gmx.de", new MailProvider("GMX", "https://www.gmx.net/")),
            Map.entry("gmx.net", new MailProvider("GMX", "https://www.gmx.net/")),
            Map.entry("gmx.ch", new MailProvider("GMX", "https://www.gmx.ch/")),
            Map.entry("web.de", new MailProvider("WEB.DE", "https://web.de/")),
            Map.entry("icloud.com", new MailProvider("iCloud Mail", "https://www.icloud.com/mail")),
            Map.entry("me.com", new MailProvider("iCloud Mail", "https://www.icloud.com/mail")),
            Map.entry("mac.com", new MailProvider("iCloud Mail", "https://www.icloud.com/mail")),
            Map.entry("proton.me", new MailProvider("Proton Mail", "https://mail.proton.me/")),
            Map.entry("protonmail.com", new MailProvider("Proton Mail", "https://mail.proton.me/")),
            Map.entry("pm.me", new MailProvider("Proton Mail", "https://mail.proton.me/")));

    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9-]+(\\.[a-z0-9-]+)+");
    private static final long CACHE_SECONDS = 6 * 3600;
    private static final int CACHE_LIMIT = 10_000;
    private static final Map<String, Cached> MX_CACHE = new ConcurrentHashMap<>();

    private record Cached(Optional<MailProvider> provider, long until) {
    }

    private MailProviders() {
    }

    /**
     * @param address what the user typed
     * @param sender  the realm's From address, for a Gmail search that finds the e-mail; may be null
     */
    static Optional<MailProvider> forAddress(String address, String sender) {
        String domain = domainOf(address);
        if (domain == null) {
            return Optional.empty();
        }
        Optional<MailProvider> provider = Optional.ofNullable(CONSUMER.get(domain));
        if (provider.isEmpty()) {
            provider = byMx(domain);
        }
        return provider.map(found -> GMAIL.equals(found.name()) ? new MailProvider(GMAIL, gmailSearch(sender)) : found);
    }

    static String domainOf(String address) {
        if (address == null) {
            return null;
        }
        int at = address.lastIndexOf('@');
        if (at < 1 || at == address.length() - 1) {
            return null;
        }
        String domain = address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        return domain.length() <= 253 && DOMAIN.matcher(domain).matches() ? domain : null;
    }

    /** Microsoft 365 and Google Workspace by their mail exchangers. */
    static Optional<MailProvider> classifyMx(List<String> exchangers) {
        for (String raw : exchangers) {
            String host = raw.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
            if (host.endsWith(".mail.protection.outlook.com") || host.endsWith(".mx.microsoft")) {
                return Optional.of(new MailProvider(OUTLOOK, OUTLOOK_365_URL));
            }
            if (host.endsWith("google.com") || host.endsWith("googlemail.com")) {
                return Optional.of(new MailProvider(GMAIL, GMAIL_URL));
            }
        }
        return Optional.empty();
    }

    /** Gmail opened on a search for our e-mail, also in spam ("in:anywhere"). */
    static String gmailSearch(String sender) {
        if (sender == null || sender.isBlank()) {
            return GMAIL_URL;
        }
        String query = "from:" + sender.trim() + " in:anywhere newer_than:1d";
        return GMAIL_URL + "#search/" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    private static Optional<MailProvider> byMx(String domain) {
        long now = System.currentTimeMillis() / 1000;
        Cached cached = MX_CACHE.get(domain);
        if (cached != null && cached.until() > now) {
            return cached.provider();
        }
        Optional<MailProvider> provider = classifyMx(lookupMx(domain));
        if (MX_CACHE.size() >= CACHE_LIMIT) {
            MX_CACHE.clear();
        }
        MX_CACHE.put(domain, new Cached(provider, now + CACHE_SECONDS));
        return provider;
    }

    /** MX hosts of the domain; empty when there are none or DNS does not answer within about a second. */
    private static List<String> lookupMx(String domain) {
        List<String> hosts = new ArrayList<>();
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "500");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext context = null;
        try {
            context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes("dns:/" + domain, new String[] {"MX"});
            Attribute mx = attributes.get("MX");
            if (mx != null) {
                NamingEnumeration<?> values = mx.getAll();
                while (values.hasMore()) {
                    String[] parts = String.valueOf(values.next()).trim().split("\\s+");
                    hosts.add(parts[parts.length - 1]);
                }
            }
        } catch (Exception | LinkageError e) {
            LOG.debugf("MX lookup for %s failed: %s", domain, e.toString());
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (Exception ignored) {
                    // nothing to clean up
                }
            }
        }
        return hosts;
    }
}
