package at.autologg.keycloak.loginlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SendTimingTest {

    private static final String REALM = "send-timing-test";

    @AfterEach
    void forget() {
        SendTiming.forget(REALM);
    }

    @Test
    void withoutSendsTheDefaultWithSomeSpread() {
        assertEquals(375, SendTiming.padMillis(REALM, 0));
        assertEquals(500, SendTiming.padMillis(REALM, 0.5));
        assertTrue(SendTiming.padMillis(REALM, 0.999) <= 625);
    }

    @Test
    void followsTheRealmsSends() {
        for (int i = 0; i < 200; i++) {
            SendTiming.record(REALM, i % 2 == 0 ? 1000 : 1400);
        }
        long middle = SendTiming.padMillis(REALM, 0.5);
        assertTrue(middle > 1100 && middle < 1300, "middle " + middle);
        long low = SendTiming.padMillis(REALM, 0);
        long high = SendTiming.padMillis(REALM, 0.999);
        assertTrue(low >= 700 && low < 1000, "low " + low);
        assertTrue(high > 1400 && high <= 1700, "high " + high);
    }

    @Test
    void aSlowMailServerNeverMakesAnAnswerWaitLongerThanTheCap() {
        for (int i = 0; i < 50; i++) {
            SendTiming.record(REALM, 60_000);
        }
        assertTrue(SendTiming.padMillis(REALM, 0.999) <= SendTiming.MAX_MILLIS);
        assertTrue(SendTiming.padMillis(REALM, 0) >= 0);
    }
}
