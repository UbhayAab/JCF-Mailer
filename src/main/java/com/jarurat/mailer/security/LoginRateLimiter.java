package com.jarurat.mailer.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one address and one client address are allowed to spend on failed sign-ins in
 * fifteen minutes: an address buys a delay, a client buys a refusal.
 *
 * This exists because the login form stopped being a test of app_user rows. It now
 * accepts every mailbox password in the organisation, and most mailbox users have no
 * app_user row at all, so the fifteen-minute account lockout in LoginAttemptListener
 * never sees them: it looks the address up, finds nothing, and counts nothing. That
 * left one POST able to test the one secret most people at the foundation actually
 * know, at whatever rate an attacker cared to send it, against an address list that
 * is published on the website.
 *
 * THE TWO KEYS ARE NOT THE SAME KIND OF CONTROL, and the previous version of this
 * class treating them as one is what made it a weapon. A hard refusal keyed on the
 * address is a hard refusal aimed at whoever owns that address: ten wrong guesses
 * and the right password answered 429 for the rest of the window, so anybody who
 * knew a staff address could hold that person out of their own mail for ten cheap
 * requests every fifteen minutes, forever. That is precisely the harm
 * ConsoleLockedException was written to remove, arriving again through the control
 * that was meant to replace it. The two fixes were working against each other and
 * neither comment mentioned the other.
 *
 * So the address budget no longer refuses anything. Past it, the attempt is delayed
 * before it is processed, by a backoff that doubles and stops at a few seconds. The
 * right password still works, it just answers slowly, so there is no victim; a run
 * of guesses down one address drops from as fast as the network allows to a handful
 * a minute. A hard cap is kept only on the client key, where the budget being spent
 * belongs to the caller spending it and refusing them costs nobody else.
 *
 * The honest residual, stated rather than hidden: a delay bounds one caller, not a
 * crowd. An attacker with a thousand distinct source addresses can run a thousand
 * delayed attempts at one mailbox at once and the delay will not stop them, only the
 * per-client cap will, at thirty each. That is a materially larger attacker than the
 * one this file used to claim to stop, and the trade is deliberate: the old shape
 * stopped that attacker on paper and in measurement stopped nobody at all, because a
 * control character in the address bought a fresh counter and the count was read
 * before the attempt and written after it.
 *
 * Deliberately in memory, the same trade OtpService makes for its per-IP burst check:
 * a restart forgives everybody, which is the wrong answer once in a while and costs
 * nothing, against a database write on every failed login and a second table to keep.
 * The counters are per process, so this bounds guessing at one application instance
 * rather than across a cluster. There is one instance today and this sentence is the
 * flag if that ever stops being true.
 */
@Component
public class LoginRateLimiter {

    /** Matches the console lockout window, so the two controls read as one policy. */
    static final int WINDOW_MINUTES = 15;

    /**
     * Ten failures at one address before the delay starts, which is above any
     * plausible run of typos on a phone keyboard. Thirty per client address before
     * the refusal, because the whole office arrives through one nginx and one NAT,
     * and a limit that a shared address trips on an ordinary morning is a limit
     * somebody will remove.
     */
    static final int PER_ADDRESS = 10;
    static final int PER_CLIENT = 30;

    /**
     * The backoff past the address budget: a quarter of a second, then doubling, and
     * never more than four seconds.
     *
     * Four seconds is where two opposite pressures meet. It has to be short enough
     * that somebody who has genuinely forgotten their password and is on their
     * twelfth try reads it as a slow page rather than a broken one, and long enough
     * that guessing costs something real. Past the budget a single caller gets about
     * fifteen attempts a minute against one address instead of as many as the network
     * will carry, and every one of them still has to be right to be worth anything.
     *
     * The cap is also why the delay is bounded at all. A sleeping request holds a
     * connection, so an unbounded delay would be a way to make the server hold
     * everything open. Virtual threads are on in application.properties, so the sleep
     * costs a heap frame rather than a platform thread, and the per-client refusal is
     * what bounds how many any one caller can have in flight.
     */
    static final long FIRST_DELAY_MILLIS = 250;
    static final long MAX_DELAY_MILLIS = 4_000;

    /**
     * A real ceiling on how many distinct keys are tracked at once, enforced by
     * eviction rather than by hope.
     *
     * It was documented as a ceiling before and was not one. The old sweep ran only
     * when the map was already full and removed only entries older than the window,
     * so inside a fifteen-minute window it freed nothing at all and simply scanned
     * the whole map on the request thread. Measured on the running application:
     * seventy thousand entries against a stated twenty thousand, and six seconds to
     * record ten thousand keys once the map had grown. Least-recently-used eviction
     * below holds the stated number exactly and costs one pointer swap.
     */
    static final int MAX_TRACKED = 20_000;

    private static final long WINDOW_MILLIS = WINDOW_MINUTES * 60_000L;

    private final Counters addresses = new Counters(MAX_TRACKED);
    private final Counters clients = new Counters(MAX_TRACKED);

    /**
     * Immutable so a reader outside compute() sees a whole window rather than a
     * count from one window and a start time from the next.
     */
    private record Window(long startedAt, int failures) {}

    /**
     * What the caller must do before it processes this attempt, and the only thing
     * this class hands back.
     *
     * There is deliberately no method that answers how a key stands without charging
     * for the answer. The old class had one, the filter read it, ran a bcrypt and a
     * round trip to the mail server, and charged the counter in a finally afterwards.
     * Every request in flight during that window read a count that had not moved yet.
     * Measured under the shipped virtual-thread configuration: four hundred concurrent
     * guesses at one address, four hundred reached authentication, none refused,
     * against a budget of ten. A read-only query is that bug waiting to be written
     * again, so the only way to ask is to reserve.
     */
    public record Decision(long retryAfterSeconds, long delayMillis) {

        /** True when this attempt must not be processed at all. */
        public boolean refused() {
            return retryAfterSeconds > 0;
        }

        /**
         * Waits out this attempt's backoff. The caller does this before it processes
         * the attempt rather than after it, because a delay after the fact slows the
         * answer down without slowing the guessing down.
         *
         * An interrupt is restored rather than swallowed, and the attempt then goes
         * ahead immediately. That is the safe way to be wrong here: the attempt has
         * already been counted, and the alternative is a request that hangs.
         */
        public void pause() {
            if (delayMillis <= 0) return;
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Charges this attempt to both counters before it is tried, and says what to do
     * with it.
     *
     * Charge first and refund on success, never read first and charge afterwards.
     * The counter tables are atomic per key, so a hundred requests arriving together
     * take a hundred different numbers out of the counter rather than all reading the
     * same one.
     *
     * The address is only charged when the client was allowed through. A caller who
     * has already spent their own budget must not also be able to run up the delay on
     * somebody else's address for free.
     */
    public Decision reserve(String rawAddress, String client) {
        long now = System.currentTimeMillis();

        Window spent = clients.charge(client, now);
        if (spent != null && spent.failures() > PER_CLIENT) {
            return new Decision(secondsLeft(spent, now), 0);
        }

        Window attempts = addresses.charge(LoginAddress.key(rawAddress), now);
        return new Decision(0, backoffMillis(attempts));
    }

    /**
     * The password was right, so this address is not under attack from whoever just
     * used it and its counter goes back to zero.
     *
     * This is the refund half of reserve() and it is what keeps a delay from
     * following somebody around: a person who fumbles their password twelve times and
     * then gets it right pays four hundred milliseconds once and starts the next
     * session on a clean counter.
     *
     * The client counter is deliberately left alone. Clearing it as well would let
     * anybody holding one working password rinse their own budget between guesses at
     * everyone else, which is exactly the shape of attack the client key is for.
     */
    public void succeeded(String rawAddress) {
        addresses.forget(LoginAddress.key(rawAddress));
    }

    /** Test seam: forget everything, as a restart would. */
    void reset() {
        addresses.clear();
        clients.clear();
    }

    /** Test seam: how many distinct addresses are being tracked right now. */
    int trackedAddresses() {
        return addresses.size();
    }

    /**
     * The address of the machine that actually sent this request, which is the last
     * element of X-Forwarded-For and not the first.
     *
     * THIS ASSUMES EXACTLY ONE TRUSTED PROXY IN FRONT OF THE APPLICATION, an nginx on
     * this box adding the standard $proxy_add_x_forwarded_for. That snippet sets the
     * header to whatever the client sent plus the socket address nginx itself saw, so
     * the last element is the only one nginx wrote and every earlier element is a
     * string the caller chose. Reading the first element, which this method used to
     * do, gets both halves of that wrong at once: an attacker sends a different first
     * element on every request and has no limit at all, and an attacker who sends the
     * office address as the first element spends everybody else's budget for them.
     * Put a second proxy in front of this and the last element becomes the inner
     * proxy's address for every request in the building, which is one shared key and
     * one shared budget, so that assumption is load bearing and this paragraph is
     * where it is written down.
     *
     * getRemoteAddr is the fallback for anything that reaches the application
     * directly, which today is the tests and any request that got past nginx.
     */
    public static String clientKey(HttpServletRequest request) {
        // Delegated, because reading the peer address correctly turned out to depend
        // on a filter that runs before this class and strips the header this method
        // used to read. ClientIp carries that reasoning and is the only reader now.
        return ClientIp.of(request);
    }

    /**
     * Zero while the address is inside its budget, then a quarter of a second
     * doubling to the cap. The shift is bounded before it is applied, because the
     * count keeps rising for as long as somebody keeps knocking and a shift past
     * sixty three wraps round to a small number or to zero.
     */
    private static long backoffMillis(Window attempts) {
        if (attempts == null) return 0;
        int over = attempts.failures() - PER_ADDRESS;
        if (over <= 0) return 0;
        long delay = FIRST_DELAY_MILLIS << Math.min(over - 1, 16);
        return Math.min(delay, MAX_DELAY_MILLIS);
    }

    private static long secondsLeft(Window window, long now) {
        long age = now - window.startedAt();
        if (age >= WINDOW_MILLIS) return 0;
        // Rounded up, so a caller that waits exactly as long as it was told is past
        // the window rather than one millisecond short of it.
        return (WINDOW_MILLIS - age + 999L) / 1000L;
    }

    /**
     * Windows are fifteen minutes long and nothing reads an expired one, so the only
     * reason these tables need sweeping at all is that the entries would otherwise
     * outlive their meaning and hold memory. Eviction already bounds the size, so
     * this is about what a quiet night leaves behind rather than about the ceiling.
     * Hourly is plenty, and it runs on a scheduler thread rather than on the request
     * path, which is where the old version of this scan ran.
     */
    @Scheduled(initialDelay = 900_000, fixedDelay = 3_600_000)
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        addresses.purge(now);
        clients.purge(now);
    }

    /**
     * A fixed-size table of counters, which is the whole of what makes MAX_TRACKED
     * true.
     *
     * LinkedHashMap in access order with removeEldestEntry is the smallest thing that
     * evicts in constant time, and Collections.synchronizedMap makes each compute()
     * atomic, which is what reserve() rests on. One monitor over a whole table would
     * be the wrong shape for a hot path; this one is entered once per failed login,
     * an event this very class holds down to a few dozen a minute, and it is held for
     * one hash lookup. The alternative, a ConcurrentHashMap beside a separate
     * eviction queue, buys nothing measurable here and is two structures to keep in
     * step.
     *
     * Eviction forgives the least recently used counter, which is the caller who has
     * been quietest, and an attacker cannot aim it: filling this table costs a real
     * attempt per key now, and one client key is allowed thirty of those in a window.
     */
    private static final class Counters {

        private final Map<String, Window> entries;

        Counters(int max) {
            this.entries = Collections.synchronizedMap(new Bounded(max));
        }

        /**
         * One more attempt against this key, atomically, answering the window as it
         * stands after the charge. A key nobody can be held responsible for, meaning
         * an empty address or a request with no client address at all, is not tracked
         * and answers null.
         */
        Window charge(String key, long now) {
            if (key == null || key.isEmpty()) return null;
            return entries.compute(key, (k, existing) ->
                    existing == null || now - existing.startedAt() >= WINDOW_MILLIS
                            ? new Window(now, 1)
                            : new Window(existing.startedAt(), existing.failures() + 1));
        }

        void forget(String key) {
            if (key != null && !key.isEmpty()) entries.remove(key);
        }

        /**
         * Iterating a synchronized map is the one operation the wrapper cannot make
         * safe by itself, so the monitor is taken by hand here.
         */
        void purge(long now) {
            synchronized (entries) {
                for (Iterator<Map.Entry<String, Window>> it = entries.entrySet().iterator(); it.hasNext(); ) {
                    if (now - it.next().getValue().startedAt() >= WINDOW_MILLIS) it.remove();
                }
            }
        }

        void clear() {
            entries.clear();
        }

        int size() {
            return entries.size();
        }
    }

    /** Named rather than anonymous only so that it can carry a serialVersionUID. */
    private static final class Bounded extends LinkedHashMap<String, Window> {

        private static final long serialVersionUID = 1L;

        private final int max;

        Bounded(int max) {
            super(64, 0.75f, true);
            this.max = max;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > max;
        }
    }
}
