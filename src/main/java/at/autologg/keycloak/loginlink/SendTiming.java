package at.autologg.keycloak.loginlink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How long sending a login e-mail takes in a realm (moving average and spread of the recent sends), so an
 * answer that sends nothing - unknown address, disabled account, registration cap reached - takes about as
 * long. Otherwise the response time would tell whether an address has an account, and someone who runs the
 * registration cap out on purpose could switch that on (review of PR #5, 28.10.2026).
 */
final class SendTiming {

    /** Before the first send of a realm. */
    static final long DEFAULT_MILLIS = 500;
    /** No answer waits longer, also when the mail server is slow. */
    static final long MAX_MILLIS = 5000;

    private record Stats(double mean, double deviation) {
    }

    private static final Map<String, Stats> REALMS = new ConcurrentHashMap<>();

    private SendTiming() {
    }

    /** A send of the realm took {@code millis}. */
    static void record(String realmId, long millis) {
        double sample = Math.max(0, Math.min(millis, MAX_MILLIS));
        REALMS.merge(realmId, new Stats(sample, sample / 4), (old, fresh) -> new Stats(
                old.mean() + (sample - old.mean()) / 8,
                old.deviation() + (Math.abs(sample - old.mean()) - old.deviation()) / 8));
    }

    /** Waits about as long as a send of the realm takes. */
    static void pad(String realmId) {
        try {
            Thread.sleep(padMillis(realmId, ThreadLocalRandom.current().nextDouble()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Even over mean +- 2 x deviation, which has the same spread as the sends;
     * {@code random} is uniform in [0, 1).
     */
    static long padMillis(String realmId, double random) {
        Stats stats = REALMS.get(realmId);
        if (stats == null) {
            return Math.round(DEFAULT_MILLIS * (0.75 + random / 2));
        }
        double wait = stats.mean() + stats.deviation() * (4 * random - 2);
        return Math.round(Math.max(0, Math.min(wait, MAX_MILLIS)));
    }

    /** Tests only. */
    static void forget(String realmId) {
        REALMS.remove(realmId);
    }
}
