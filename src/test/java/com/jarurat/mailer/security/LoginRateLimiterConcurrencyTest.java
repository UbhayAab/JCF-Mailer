package com.jarurat.mailer.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Four hundred guesses at one address arriving at once, which is the shape that
 * defeated this limiter completely and which nothing in the tree exercised.
 *
 * The old filter read the counter, ran a bcrypt at strength twelve and a round trip
 * to the mail server, and charged the counter afterwards. Every request that arrived
 * inside that gap read a count that had not moved yet. Measured on the running
 * application under the shipped spring.threads.virtual.enabled: four hundred
 * concurrent guesses at one address, four hundred reached authentication, none
 * refused, against a budget of ten. Under the test profile with virtual threads off
 * it was twenty eight of a hundred and twenty, which is the trap in this whole area:
 * the platform thread pool was accidentally hiding most of the bug, and the shipped
 * configuration removes the pool.
 *
 * So this test runs on virtual threads deliberately, to match the shipped
 * configuration rather than the comfortable one, and it asserts an exact number
 * rather than a bound. Exactly ten callers may find the address inside its budget no
 * matter how many arrive together; a read-then-write counter answers four hundred,
 * and any weakening of the atomic charge answers something between the two.
 */
class LoginRateLimiterConcurrencyTest {

    private static final String ADDRESS = "priya@jarurat.care";
    private static final int CALLERS = 400;

    @Test
    @DisplayName("only the budget gets through when the whole attack arrives at once")
    void concurrentReservationsCannotOverspendTheAddressBudget() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter();
        AtomicInteger free = new AtomicInteger();
        AtomicInteger delayed = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        // Every caller carries its own client address, so this is the botnet shape and
        // the per-client cap is not what is being measured. The address counter is on
        // its own here, exactly as it would be against a thousand hosts.
        CountDownLatch ready = new CountDownLatch(CALLERS);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CALLERS);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < CALLERS; i++) {
            String client = "10." + (i / 250) + "." + (i / 250) + "." + (i % 250);
            threads.add(Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    go.await();
                    LoginRateLimiter.Decision decision = limiter.reserve(ADDRESS, client);
                    if (decision.refused()) refused.incrementAndGet();
                    else if (decision.delayMillis() > 0) delayed.incrementAndGet();
                    else free.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }));
        }

        assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        for (Thread thread : threads) thread.join();

        assertThat(free.get()).isEqualTo(LoginRateLimiter.PER_ADDRESS);
        assertThat(delayed.get()).isEqualTo(CALLERS - LoginRateLimiter.PER_ADDRESS);
        // Nobody is refused on an address, however hard it is hit. That is the other
        // half of the design and it is what stops this being a way to take somebody's
        // mailbox away from them.
        assertThat(refused.get()).isZero();
    }

    @Test
    @DisplayName("concurrent reservations against one client stop exactly at its cap")
    void concurrentReservationsCannotOverspendTheClientBudget() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter();
        AtomicInteger allowed = new AtomicInteger();

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CALLERS);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < CALLERS; i++) {
            String address = "person" + i + "@jarurat.care";
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    if (!limiter.reserve(address, "203.0.113.9").refused()) allowed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }));
        }

        go.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        for (Thread thread : threads) thread.join();

        assertThat(allowed.get()).isEqualTo(LoginRateLimiter.PER_CLIENT);
    }
}
