package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.MailCredentialStore;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.Outgoing;
import com.jarurat.mailer.models.QueuedMessage;
import com.jarurat.mailer.repositories.QueuedMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The four properties this feature has to have, against a real database.
 *
 * These are not unit tests of the service's arithmetic. Every one of them is about
 * something that only exists once rows and concurrency are real: that two workers
 * reaching for the same message produce one send, that a cancel arriving after the
 * window is refused rather than quietly working, that a timestamp with a fraction on
 * it is still found by the due query, and that one mailbox cannot touch another's
 * outbox. Three of the four are invisible to any test that mocks the repository.
 *
 * The context runs with its own in-memory database and with the background pass
 * switched off. Its own database because two Spring contexts sharing one named H2
 * schema will drop each other's tables on boot, and the pass switched off because a
 * loop firing on a two second timer in the middle of an assertion is how a queue test
 * becomes flaky and then becomes ignored. Every pass here is driven by hand.
 */
@SpringBootTest(properties = {
        "jarurat.mail.outbox.autostart=false",
        "jarurat.mail.outbox.undo-seconds=10",
        "jarurat.mail.outbox.locked-grace-hours=72",
        "jarurat.mail.outbox.stall-minutes=5",
        "spring.datasource.url=jdbc:h2:mem:outboxtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
class OutboxServiceTest {

    private static final String PRIYA = "priya@jarurat.care";
    private static final String SUPPORT = "support@jarurat.care";

    @Autowired OutboxService outbox;
    @Autowired QueuedMessageRepository queue;

    @MockitoBean MailService mail;
    @MockitoBean MailCredentialStore credentials;

    @BeforeEach
    void bothMailboxesAreOpenAndTheMailServerWorks() {
        queue.deleteAll();
        when(credentials.knows(anyString())).thenReturn(true);
        when(mail.send(anyString(), any(Outgoing.class))).thenReturn("email-1");
    }

    private static Outgoing letter() {
        return Outgoing.message(List.of("dr.rao@example.org"), List.of(),
                "Camp on the 12th", "<p>Dear Dr Rao,</p>", "Dear Dr Rao,");
    }

    // ------------------------------------------------------------------ the race

    @Test
    @DisplayName("eight workers reaching for one due message produce exactly one send")
    void aDueMessageIsSentOnceHoweverManyWorkersSeeIt() throws Exception {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        LocalDateTime due = row.getSendAt().plusSeconds(1);

        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger threw = new AtomicInteger();
        List<Thread> threads = new java.util.ArrayList<>();

        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    claimed.addAndGet(outbox.runDue(due));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // A database that answers a lock conflict with an exception rather
                    // than a wait is still a worker that did not win, and the assertion
                    // that matters is the send count either way.
                    threw.incrementAndGet();
                }
            });
            threads.add(t);
            t.start();
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (Thread t : threads) t.join(20_000);

        // The one that matters. Whatever the eight threads did to each other, the mail
        // server was handed this message once.
        verify(mail, times(1)).send(anyString(), any(Outgoing.class));
        assertThat(claimed.get()).isEqualTo(1);
        assertThat(queue.findById(row.getId()).orElseThrow().getState())
                .isEqualTo(QueuedMessage.SENT);
        assertThat(queue.findById(row.getId()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a claim already taken cannot be taken again")
    void theClaimIsWonOnce() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        LocalDateTime now = row.getSendAt().plusSeconds(1);

        assertThat(queue.claim(row.getId(), now)).isEqualTo(1);
        assertThat(queue.claim(row.getId(), now)).isEqualTo(0);
    }

    @Test
    @DisplayName("a cancel that arrives while the loop is claiming cannot also win")
    void cancelAndClaimCannotBothWin() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        LocalDateTime now = row.getSendAt().plusSeconds(1);

        assertThat(queue.claim(row.getId(), now)).isEqualTo(1);
        assertThat(queue.cancel(row.getId(), PRIYA, now, null)).isEqualTo(0);
        assertThat(queue.findById(row.getId()).orElseThrow().getState())
                .isEqualTo(QueuedMessage.SENDING);
    }

    // ------------------------------------------------------------------ the window

    @Test
    @DisplayName("undo inside the window stops the message and nothing is sent")
    void cancelInsideTheWindowStopsIt() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);

        OutboxService.Outcome outcome = outbox.cancel(PRIYA, row.getId());

        assertThat(outcome.ok()).isTrue();
        assertThat(queue.findById(row.getId()).orElseThrow().getState())
                .isEqualTo(QueuedMessage.CANCELLED);

        outbox.runDue(row.getSendAt().plusMinutes(1));
        verify(mail, never()).send(anyString(), any(Outgoing.class));
    }

    @Test
    @DisplayName("undo after the window is refused in words, and the message still goes")
    void cancelAfterTheWindowIsRefused() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        // Reach into the row rather than sleeping for the window: what is being tested
        // is the rule, not the ten seconds.
        row.setSendAt(LocalDateTime.now().minusSeconds(1));
        queue.save(row);

        OutboxService.Outcome outcome = outbox.cancel(PRIYA, row.getId());

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.problem()).isEqualTo(OutboxService.Problem.TOO_LATE);
        assertThat(queue.findById(row.getId()).orElseThrow().getState())
                .isEqualTo(QueuedMessage.HELD);

        assertThat(outbox.runDue(LocalDateTime.now())).isEqualTo(1);
        verify(mail, times(1)).send(anyString(), any(Outgoing.class));
    }

    @Test
    @DisplayName("a message that has already gone says so, and does not say it was recalled")
    void cancelAfterSendingSaysWhatIsTrue() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        outbox.runDue(row.getSendAt().plusSeconds(1));

        OutboxService.Outcome outcome = outbox.cancel(PRIYA, row.getId());

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.message()).contains("already been sent");
        assertThat(outcome.message()).contains("cannot be recalled");
        assertThat(outcome.message().toLowerCase()).doesNotContain("recalled it");
    }

    // ------------------------------------------------------------------ seconds

    @Test
    @DisplayName("a scheduled time with a fraction of a second on it is stored whole and still found")
    void everyTimestampIsTruncatedToWholeSeconds() {
        LocalDateTime ragged = LocalDateTime.now().plusMinutes(5).withNano(987_654_321);

        QueuedMessage row = outbox.queue(PRIYA, letter(), ragged, null);
        QueuedMessage stored = queue.findById(row.getId()).orElseThrow();

        assertThat(stored.getSendAt().getNano()).isZero();
        assertThat(stored.getQueuedAt().getNano()).isZero();
        assertThat(stored.getSendAt()).isEqualTo(ragged.withNano(0));

        // The reason the truncation exists. Postgres would store 09:00:00.987 as
        // 09:00:00 and H2 rounds it to 09:00:01, so a due query run at the truncated
        // second finds the row on one and misses it on the other. Truncating at the
        // boundary makes this assertion true on both.
        List<QueuedMessage> due = queue.findDue(ragged.withNano(0), PageRequest.of(0, 10));
        assertThat(due).extracting(QueuedMessage::getId).contains(row.getId());
    }

    @Test
    @DisplayName("the undo deadline is a whole second too, so the countdown and the loop agree")
    void theUndoDeadlineIsWhole() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);

        assertThat(row.getSendAt().getNano()).isZero();
        assertThat(row.getClaimedAt()).isNull();
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("one mailbox cannot see, cancel or dismiss another mailbox's outbox")
    void mailboxesAreIsolated() {
        QueuedMessage hers = outbox.queue(PRIYA, letter(), null, null);
        QueuedMessage theirs = outbox.queue(SUPPORT, letter(), null, null);

        assertThat(outbox.open(PRIYA)).extracting(QueuedMessage::getId).containsExactly(hers.getId());
        assertThat(outbox.open(SUPPORT)).extracting(QueuedMessage::getId).containsExactly(theirs.getId());

        OutboxService.Outcome stolen = outbox.cancel(SUPPORT, hers.getId());
        assertThat(stolen.ok()).isFalse();
        assertThat(stolen.problem()).isEqualTo(OutboxService.Problem.NOT_FOUND);
        assertThat(queue.findById(hers.getId()).orElseThrow().getState()).isEqualTo(QueuedMessage.HELD);

        assertThat(outbox.acknowledge(SUPPORT, hers.getId()).problem())
                .isEqualTo(OutboxService.Problem.NOT_FOUND);
        assertThat(outbox.replace(SUPPORT, hers.getId(), letter(), null, null).problem())
                .isEqualTo(OutboxService.Problem.NOT_FOUND);
    }

    @Test
    @DisplayName("a message goes out from the mailbox that queued it and no other")
    void theSendUsesTheQueueingMailbox() {
        QueuedMessage row = outbox.queue(SUPPORT, letter(), null, null);

        outbox.runDue(row.getSendAt().plusSeconds(1));

        verify(mail).send(org.mockito.ArgumentMatchers.eq(SUPPORT), any(Outgoing.class));
    }

    // ------------------------------------------------------------------ failure

    @Test
    @DisplayName("a send that the mail server refuses ends up somewhere a person will see it")
    void aRefusedSendIsVisible() {
        when(mail.send(anyString(), any(Outgoing.class)))
                .thenThrow(new MailException(MailException.Kind.METHOD, "invalidEmail",
                        "Mail server refused the message: invalidEmail", null));

        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        outbox.runDue(row.getSendAt().plusSeconds(1));

        QueuedMessage failed = queue.findById(row.getId()).orElseThrow();
        assertThat(failed.getState()).isEqualTo(QueuedMessage.FAILED);
        assertThat(failed.getLastError()).contains("refused");
        assertThat(failed.getLastError()).contains("Nothing was sent");

        assertThat(outbox.unseenFailures(PRIYA)).isEqualTo(1);
        assertThat(outbox.open(PRIYA)).extracting(QueuedMessage::getId).contains(row.getId());

        outbox.acknowledge(PRIYA, row.getId());
        assertThat(outbox.unseenFailures(PRIYA)).isZero();
        assertThat(outbox.open(PRIYA)).isEmpty();
    }

    @Test
    @DisplayName("a mail server that was unreachable is retried, and is not sent twice")
    void aConnectionThatNeverOpenedIsRetried() {
        when(mail.send(anyString(), any(Outgoing.class)))
                .thenThrow(new MailException(MailException.Kind.TRANSPORT,
                        "Could not reach the mail server.", new ConnectException("refused")));

        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        LocalDateTime firstTry = row.getSendAt().plusSeconds(1);
        outbox.runDue(firstTry);

        QueuedMessage back = queue.findById(row.getId()).orElseThrow();
        assertThat(back.getState()).isEqualTo(QueuedMessage.HELD);
        assertThat(back.getSendAt()).isAfter(firstTry);
        assertThat(back.getAttempts()).isEqualTo(1);
        assertThat(back.getLastError()).contains("Trying again");

        // A retry the moment the loop runs again would be a busy loop, so the row is
        // genuinely not due yet.
        assertThat(outbox.runDue(firstTry)).isZero();
        verify(mail, times(1)).send(anyString(), any(Outgoing.class));
    }

    @Test
    @DisplayName("a claim nobody came back from fails with an honest answer rather than a second send")
    void aStalledClaimIsNeverRetried() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        row.setState(QueuedMessage.SENDING);
        row.setClaimedAt(LocalDateTime.now().minusHours(1));
        queue.save(row);

        outbox.runDue(LocalDateTime.now());

        QueuedMessage stalled = queue.findById(row.getId()).orElseThrow();
        assertThat(stalled.getState()).isEqualTo(QueuedMessage.FAILED);
        assertThat(stalled.getLastError()).contains("may or may not have gone out");
        verify(mail, never()).send(anyString(), any(Outgoing.class));
    }

    @Test
    @DisplayName("a mailbox nobody has unlocked keeps its place in the queue")
    void aLockedMailboxWaits() {
        when(credentials.knows(PRIYA)).thenReturn(false);

        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        assertThat(outbox.runDue(row.getSendAt().plusSeconds(1))).isZero();

        QueuedMessage waiting = queue.findById(row.getId()).orElseThrow();
        assertThat(waiting.getState()).isEqualTo(QueuedMessage.HELD);
        assertThat(waiting.getLastError()).contains("Waiting for");
        verify(mail, never()).send(anyString(), any(Outgoing.class));

        // And it goes the moment somebody opens the mailbox, which is what makes
        // waiting the right answer rather than failing at the scheduled minute.
        when(credentials.knows(PRIYA)).thenReturn(true);
        assertThat(outbox.runDue(row.getSendAt().plusSeconds(2))).isEqualTo(1);
    }

    @Test
    @DisplayName("a mailbox still locked days later fails visibly instead of waiting forever")
    void aLockedMailboxEventuallyGivesUp() {
        when(credentials.knows(PRIYA)).thenReturn(false);

        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        outbox.runDue(row.getSendAt().plusHours(80));

        QueuedMessage given = queue.findById(row.getId()).orElseThrow();
        assertThat(given.getState()).isEqualTo(QueuedMessage.FAILED);
        assertThat(given.getLastError()).contains("Nobody opened");
        assertThat(outbox.unseenFailures(PRIYA)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ editing

    @Test
    @DisplayName("editing a queued message leaves exactly one message behind")
    void editingLeavesOneMessage() {
        QueuedMessage first = outbox.queue(PRIYA, letter(), LocalDateTime.now().plusHours(2), null);

        Outgoing rewritten = Outgoing.message(List.of("dr.rao@example.org"), List.of(),
                "Camp on the 13th", "<p>Dear Dr Rao,</p>", "Dear Dr Rao,");
        OutboxService.Outcome outcome = outbox.replace(PRIYA, first.getId(), rewritten, null, null);

        assertThat(outcome.ok()).isTrue();
        assertThat(queue.findById(first.getId()).orElseThrow().getState())
                .isEqualTo(QueuedMessage.CANCELLED);
        assertThat(queue.findById(first.getId()).orElseThrow().getReplacedById())
                .isEqualTo(outcome.row().getId());

        List<QueuedMessage> open = outbox.open(PRIYA);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getSubject()).isEqualTo("Camp on the 13th");
        // The time it was scheduled for survives an edit that does not name a new one.
        assertThat(open.get(0).getSendAt()).isEqualTo(first.getSendAt());
    }

    @Test
    @DisplayName("an edit that arrives after the loop has claimed the message changes nothing")
    void editingAfterTheClaimIsRefused() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, null);
        row.setSendAt(LocalDateTime.now().minusSeconds(1));
        queue.save(row);

        OutboxService.Outcome outcome = outbox.replace(PRIYA, row.getId(), letter(), null, null);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.problem()).isEqualTo(OutboxService.Problem.TOO_LATE);
        assertThat(queue.findOpen(PRIYA)).hasSize(1);
        assertThat(queue.findById(row.getId()).orElseThrow().getState()).isEqualTo(QueuedMessage.HELD);
    }

    // ------------------------------------------------------------------ the shape

    @Test
    @DisplayName("what comes out of the row is the message that went in, blind copies included")
    void theQueuedMessageSurvivesTheRoundTrip() {
        Outgoing message = Outgoing.message(
                        List.of("dr.rao@example.org", "sister@example.org"),
                        List.of("priya@jarurat.care"),
                        "Camp on the 12th", "<p>Dear all,</p>", "Dear all,")
                .withBcc(List.of("audit@jarurat.care"))
                .inThread("parent-id@example.org", List.of("root@example.org"));

        QueuedMessage row = outbox.queue(PRIYA, message, null, "email-parent");
        Outgoing back = queue.findById(row.getId()).orElseThrow().toOutgoing();

        assertThat(back.to()).containsExactly("dr.rao@example.org", "sister@example.org");
        assertThat(back.cc()).containsExactly("priya@jarurat.care");
        assertThat(back.bcc()).containsExactly("audit@jarurat.care");
        assertThat(back.subject()).isEqualTo("Camp on the 12th");
        assertThat(back.html()).isEqualTo("<p>Dear all,</p>");
        assertThat(back.text()).isEqualTo("Dear all,");
        assertThat(back.inReplyTo()).isEqualTo("parent-id@example.org");
        assertThat(back.references()).containsExactly("root@example.org");
        assertThat(back.everyRecipient()).hasSize(4);
    }

    @Test
    @DisplayName("the reply arrow is set after the reply has gone, and never before")
    void theParentIsMarkedAnsweredOnlyOnceTheReplyHasGone() {
        QueuedMessage row = outbox.queue(PRIYA, letter(), null, "email-parent");

        verify(mail, never()).setKeyword(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        outbox.runDue(row.getSendAt().plusSeconds(1));
        verify(mail).setKeyword(PRIYA, "email-parent", "answered", true);
    }

    @Test
    @DisplayName("a message with no recipients is refused while somebody is still looking at it")
    void anEmptyMessageIsRefusedAtQueueTime() {
        Outgoing empty = Outgoing.message(List.of(), List.of(), "Nobody", "<p></p>", "");

        try {
            outbox.queue(PRIYA, empty, null, null);
            org.junit.jupiter.api.Assertions.fail("an empty message should not be queued");
        } catch (MailException e) {
            assertThat(e.getKind()).isEqualTo(MailException.Kind.PROTOCOL);
            assertThat(e.getMessage()).contains("recipient");
        }
        assertThat(queue.findOpen(PRIYA)).isEmpty();
    }

    @Test
    @DisplayName("a time in the past is refused rather than sent immediately by accident")
    void aPastTimeIsRefused() {
        try {
            outbox.queue(PRIYA, letter(), LocalDateTime.now().minusMinutes(1), null);
            org.junit.jupiter.api.Assertions.fail("a past time should not be accepted");
        } catch (MailException e) {
            assertThat(e.getMessage()).contains("already passed");
        }
    }
}
