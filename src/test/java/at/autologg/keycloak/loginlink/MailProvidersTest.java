package at.autologg.keycloak.loginlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MailProvidersTest {

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
        // Not allowed and not cached: no DNS query, no button.
        assertTrue(MailProviders.forAddress("someone@not-looked-up-al5388.example", null, false).isEmpty());
        // Consumer domains need no lookup.
        assertEquals("Outlook", MailProviders.forAddress("max@outlook.com", null, false).orElseThrow().name());
    }
}
