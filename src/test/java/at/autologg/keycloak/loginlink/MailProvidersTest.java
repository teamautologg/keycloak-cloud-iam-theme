package at.autologg.keycloak.loginlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MailProvidersTest {

    private final Function<String, List<String>> realResolver = MailProviders.resolver;

    @AfterEach
    void realResolverAgain() {
        MailProviders.resolver = realResolver;
    }

    @Test
    void consumerDomainsNeedNoLookup() {
        assertEquals("Outlook", MailProviders.forAddress("Max@Hotmail.de", null).orElseThrow().name());
        assertEquals("https://outlook.live.com/mail/0/", MailProviders.forAddress("max@outlook.com", null).orElseThrow().url());
        assertEquals("GMX", MailProviders.forAddress("max@gmx.at", null).orElseThrow().name());
        assertEquals("https://www.gmx.at/", MailProviders.forAddress("max@gmx.at", null).orElseThrow().url());
    }

    @Test
    void outlookHasTheDesktopAppGmailDoesNot() {
        assertEquals("ms-outlook:", MailProviders.forAddress("max@hotmail.de", null).orElseThrow().appUrl());
        assertEquals("ms-outlook:", MailProviders.classifyMx(List.of("contoso-com.mail.protection.outlook.com")).orElseThrow().appUrl());
        assertNull(MailProviders.forAddress("max@gmail.com", "no-reply@autologg.com").orElseThrow().appUrl());
        assertNull(MailProviders.forAddress("max@gmx.at", null).orElseThrow().appUrl());
    }

    @Test
    void gmailOpensOnASearchForOurMail() {
        String url = MailProviders.forAddress("max@gmail.com", "no-reply@autologg.com").orElseThrow().url();
        assertEquals("https://mail.google.com/mail/u/0/#search/from%3Ano-reply%40autologg.com+in%3Aanywhere+newer_than%3A1d", url);
        assertEquals("https://mail.google.com/mail/u/0/", MailProviders.forAddress("max@gmail.com", null).orElseThrow().url());
    }

    @Test
    void companyDomainsByMailExchanger() {
        assertEquals("https://outlook.office.com/mail/",
                MailProviders.classifyMx(List.of("contoso-com.mail.protection.outlook.com.")).orElseThrow().url());
        assertEquals("https://outlook.office.com/mail/",
                MailProviders.classifyMx(List.of("contoso-com.o-v1.mx.microsoft")).orElseThrow().url());
        assertEquals("Gmail", MailProviders.classifyMx(List.of("smtp.google.com.")).orElseThrow().name());
        assertEquals("Gmail", MailProviders.classifyMx(List.of("aspmx.l.google.com", "alt1.aspmx.l.google.com")).orElseThrow().name());
        assertTrue(MailProviders.classifyMx(List.of("mx01.mail.example.at")).isEmpty());
        assertTrue(MailProviders.classifyMx(List.of()).isEmpty());
    }

    @Test
    void onlyPlainDomainsAreLookedUp() {
        assertEquals("autologg.com", MailProviders.domainOf("thomas@AutoLogg.com"));
        assertNull(MailProviders.domainOf("thomas"));
        assertNull(MailProviders.domainOf("thomas@"));
        assertNull(MailProviders.domainOf("@autologg.com"));
        assertNull(MailProviders.domainOf("thomas@localhost"));
        assertNull(MailProviders.domainOf("thomas@exa mple.com"));
        assertNull(MailProviders.domainOf(null));
    }

    @Test
    void companyDomainsAreLookedUpOnlyWhenAllowed() {
        List<String> asked = new ArrayList<>();
        MailProviders.resolver = domain -> {
            asked.add(domain);
            return List.of("contoso-com.mail.protection.outlook.com");
        };
        // Not allowed and not cached: no DNS query, no button.
        assertTrue(MailProviders.forAddress("max@gate-al5388.test", null, false).isEmpty());
        assertTrue(asked.isEmpty());
        // Consumer domains need no lookup.
        assertEquals("Outlook", MailProviders.forAddress("max@outlook.com", null, false).orElseThrow().name());
        assertTrue(asked.isEmpty());
        // Allowed: one query, then the cached answer also where no query is allowed.
        assertEquals("Outlook", MailProviders.forAddress("max@gate-al5388.test", null, true).orElseThrow().name());
        assertEquals("Outlook", MailProviders.forAddress("eva@gate-al5388.test", null, false).orElseThrow().name());
        assertEquals(List.of("gate-al5388.test"), asked);
    }

    @Test
    void noAnswerFromDnsIsNoProviderForNowNotForHours() {
        MailProviders.resolver = domain -> null;
        assertTrue(MailProviders.forAddress("max@timeout-al5388.test", null, true).isEmpty());
        MailProviders.resolver = domain -> List.of("aspmx.l.google.com");
        // Cached for a few minutes only; within them the button stays off.
        assertTrue(MailProviders.forAddress("max@timeout-al5388.test", null, false).isEmpty());
    }
}
