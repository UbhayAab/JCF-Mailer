package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.Attachment;
import com.jarurat.mailer.mail.MailCredentialStore;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.Outgoing;
import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.models.QueuedMessage;
import com.jarurat.mailer.repositories.QueuedMessageRepository;
import com.jarurat.mailer.services.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Holds a message for a while and then sends it, which is all that undo send and
 * schedule send actually are.
 *
 * Undo send is worth being precise about, because the word invites a lie. Nothing
 * here recalls anything. Gmail, Fastmail and Proton all do the same thing this does:
 * they sit on the message for a few seconds and only hand it to a mail server once
 * the window has closed, so what Undo cancels is a send that has not happened yet.
 * Once the window is gone the message is gone, and every sentence this class produces
 * says so rather than offering a button that will fail. A recall that a person
 * discovers was fictional, at the moment they most needed it to be real, is worse
 * than never having offered one.
 *
 * The loop is modelled on JourneyEngine rather than being a second scheduler: one
 * @Scheduled method, a bounded page of due rows, and work that is safe to repeat
 * because every state change is a guarded UPDATE. See QueuedMessageRepository for
 * why the guards are in the statements and not in this class.
 *
 * Two guarantees, stated plainly because they are the reason this class exists.
 *
 * A message is never sent twice. The only path to MailService.send runs behind
 * QueuedMessageRepository.claim, which turns HELD into SENDING in one statement and
 * answers 1 to exactly one caller however many are asking. That claim is committed
 * before anything is handed to the mail server, so a second worker, a second instance
 * or a restarted process finds SENDING and stops. Cancelling is the same statement
 * from the other side: it only ever moves a row out of HELD, so a cancel and a claim
 * cannot both win.
 *
 * A message is never silently lost, which is a weaker promise than never lost and it
 * is the honest one. If this process dies between the claim and the answer, nobody
 * can know whether Stalwart accepted the submission, and the two ways of guessing are
 * both wrong: retrying can deliver a donor two copies, and dropping it can lose a
 * message somebody believes they sent. So it does neither. The stalled row is marked
 * FAILED with a sentence that says delivery is unknown and asks the person to check
 * Sent, and it sits in the outbox listing until they have seen it. Everything the
 * loop can decide for itself, a mail server that was down, a mailbox nobody has
 * unlocked, is retried or waited on instead.
 */
@Service
public class OutboxService {

    /** Where a refusal came from, so the API can pick a status and the screen a sentence. */
    public enum Problem {
        NONE,
        /** No such message in this mailbox, which is also the answer for somebody else's. */
        NOT_FOUND,
        /** It is past the moment we promised it could still be stopped. */
        TOO_LATE
    }

    /** What happened to a cancel, a reschedule or an acknowledgement. */
    public record Outcome(boolean ok, Problem problem, String message, QueuedMessage row) {

        public static Outcome done(String message, QueuedMessage row) {
            return new Outcome(true, Problem.NONE, message, row);
        }

        public static Outcome refused(Problem problem, String message, QueuedMessage row) {
            return new Outcome(false, problem, message, row);
        }
    }

    private final QueuedMessageRepository queue;
    private final MailService mail;
    private final MailCredentialStore credentials;
    private final AuditService audit;
    private final MessageLogService messageLog;

    /**
     * How long an ordinary send is held. Ten seconds is Fastmail's fifteen and Gmail's
     * five split down the middle, and the number that matters is not the value but
     * that the screen is told it rather than assuming one, which is why it is on every
     * response this feature produces.
     */
    private final int undoSeconds;

    /** How far ahead a message may be scheduled. */
    private final int maxDaysAhead;

    /**
     * How far ahead a message carrying files may be scheduled.
     *
     * The blobs are uploaded when the message is queued and only referenced when it is
     * sent, and nothing in Stalwart's documentation says how long it keeps a blob that
     * no message points at yet. Rather than discover the answer at 9am on a Monday
     * with a donor waiting, a message with attachments is only accepted inside a
     * window short enough that the question does not arise, and anything longer is
     * refused while the person is still looking at the screen.
     */
    private final int attachmentHours;

    /**
     * How long a due message waits for its mailbox to be unlocked before it is given
     * up on.
     *
     * This is the ugly consequence of MailCredentialStore holding the mailbox password
     * in heap for the length of a session. A message scheduled for Monday can only be
     * sent if somebody has that mailbox open when Monday comes, and the honest
     * behaviour while nobody does is to wait and say why rather than to fail at the
     * scheduled minute. It stops being honest eventually, so there is a deadline, and
     * after it the message fails visibly. OAUTHBEARER, which that class describes as
     * the answer it is waiting for, removes this whole paragraph.
     */
    private final int lockedGraceHours;

    /** How long a claim may be outstanding before the worker holding it is presumed dead. */
    private final int stallMinutes;

    /** How many times a message may be re-queued after a failure that reached nothing. */
    private final int maxAttempts;

    /** How many due messages one pass will take. */
    private final int batchSize;

    /** How long a settled row is kept before the nightly purge takes it. */
    private final int retentionDays;

    /**
     * Whether the scheduled tick does anything.
     *
     * A test that drives runDue itself must be able to switch the background pass off,
     * because a loop firing on its own timer in the middle of an assertion is the
     * classic way a queue test becomes flaky and then becomes ignored.
     */
    private final boolean autostart;

    public OutboxService(QueuedMessageRepository queue,
                         MailService mail,
                         MailCredentialStore credentials,
                         AuditService audit,
                         MessageLogService messageLog,
                         @Value("${jarurat.mail.outbox.undo-seconds:10}") int undoSeconds,
                         @Value("${jarurat.mail.outbox.max-days-ahead:60}") int maxDaysAhead,
                         @Value("${jarurat.mail.outbox.attachment-hours:24}") int attachmentHours,
                         @Value("${jarurat.mail.outbox.locked-grace-hours:72}") int lockedGraceHours,
                         @Value("${jarurat.mail.outbox.stall-minutes:5}") int stallMinutes,
                         @Value("${jarurat.mail.outbox.max-attempts:3}") int maxAttempts,
                         @Value("${jarurat.mail.outbox.batch:50}") int batchSize,
                         @Value("${jarurat.mail.outbox.retention-days:30}") int retentionDays,
                         @Value("${jarurat.mail.outbox.autostart:true}") boolean autostart) {
        this.queue = queue;
        this.mail = mail;
        this.credentials = credentials;
        this.audit = audit;
        this.messageLog = messageLog;
        this.undoSeconds = Math.max(0, undoSeconds);
        this.maxDaysAhead = Math.max(1, maxDaysAhead);
        this.attachmentHours = Math.max(1, attachmentHours);
        this.lockedGraceHours = Math.max(1, lockedGraceHours);
        this.stallMinutes = Math.max(1, stallMinutes);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.batchSize = Math.max(1, batchSize);
        this.retentionDays = Math.max(1, retentionDays);
        this.autostart = autostart;
    }

    public int undoSeconds() { return undoSeconds; }

    public int maxDaysAhead() { return maxDaysAhead; }

    public int attachmentHours() { return attachmentHours; }

    // ------------------------------------------------------------------ queueing

    /**
     * Accepts a message for later and answers the row it now lives in.
     *
     * A null sendAt means an ordinary send, held for the undo window. Everything that
     * can refuse the message is decided here, while somebody is still looking at the
     * compose sheet, because the alternative is a validation failure at three in the
     * morning that nobody can act on until the next day.
     */
    public QueuedMessage queue(String mailbox, Outgoing message, LocalDateTime sendAt,
                               String replyToEmailId) {
        LocalDateTime now = clock();
        String owner = normalise(mailbox);
        boolean scheduled = sendAt != null;
        LocalDateTime when = dueTime(sendAt, now);
        check(message, sendAt, now);

        QueuedMessage row = queue.save(new QueuedMessage(owner,
                scheduled ? QueuedMessage.SCHEDULED : QueuedMessage.UNDO,
                when, now, message, replyToEmailId));

        audit.record(scheduled ? "MAIL_SCHEDULED" : "MAIL_QUEUED", owner,
                "outbox " + row.getId() + " for " + when
                        + ", subject " + (row.getSubject().isBlank() ? "(none)" : row.getSubject()));
        return row;
    }

    private LocalDateTime dueTime(LocalDateTime sendAt, LocalDateTime now) {
        return sendAt != null
                ? QueuedMessage.whole(sendAt)
                : QueuedMessage.whole(now.plusSeconds(undoSeconds));
    }

    /**
     * Every reason this outbox will not take a message, in one place and raised before
     * anything is written.
     *
     * Separate from queue() because an edit has to run these checks against the new
     * text before the old message is cancelled. Checking afterwards would mean a
     * mistyped time turning a message somebody had scheduled into no message at all,
     * which is the one thing an outbox may never do.
     */
    private void check(Outgoing message, LocalDateTime sendAt, LocalDateTime now) {
        if (message.everyRecipient().isEmpty()) {
            throw new MailException(MailException.Kind.PROTOCOL, "Add at least one recipient.");
        }
        if (message.everyRecipient().size() > Outgoing.MAX_RECIPIENTS) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "That message names " + message.everyRecipient().size()
                            + " recipients and one message may carry " + Outgoing.MAX_RECIPIENTS
                            + ". Send a list this size from Campaign Studio, which throttles it and "
                            + "records what happened to each address.");
        }
        if (sendAt == null) return;

        LocalDateTime when = QueuedMessage.whole(sendAt);
        if (!when.isAfter(now)) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "That time has already passed. Pick a time in the future, or send it now.");
        }
        if (when.isAfter(now.plusDays(maxDaysAhead))) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "This outbox holds a message for at most " + maxDaysAhead
                            + " days. Pick a nearer time.");
        }
        if (!message.attachments().isEmpty() && when.isAfter(now.plusHours(attachmentHours))) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "A message with files attached can only be scheduled " + attachmentHours
                            + " hours ahead. The mail server is holding those files for us unsent "
                            + "and nothing promises they will still be there next week, so this is "
                            + "refused now rather than failing later. Send it sooner, or send it now.");
        }
    }

    /**
     * Stops a message, if it is still ours to stop.
     *
     * The decision is one statement and the reading afterwards is only ever for
     * wording. That ordering matters: if this looked first and then cancelled, the
     * sender loop could claim the row in between and the person would be told their
     * message was stopped while it was on its way out.
     */
    public Outcome cancel(String mailbox, Long id) {
        return cancel(mailbox, id, null);
    }

    private Outcome cancel(String mailbox, Long id, Long replacedBy) {
        LocalDateTime now = clock();
        String owner = normalise(mailbox);

        if (queue.cancel(id, owner, now, replacedBy) == 1) {
            audit.record("MAIL_UNSENT", owner, "outbox " + id + " cancelled before it went");
            return Outcome.done("That message was stopped and nothing was sent.",
                    queue.findByIdAndMailbox(id, owner).orElse(null));
        }

        QueuedMessage row = queue.findByIdAndMailbox(id, owner).orElse(null);
        if (row == null) {
            return Outcome.refused(Problem.NOT_FOUND, "There is no such message in your outbox.", null);
        }
        return Outcome.refused(Problem.TOO_LATE, lateSentence(row), row);
    }

    /**
     * Replaces a queued message with a rewritten one, which is what editing something
     * that has not gone yet has to mean.
     *
     * The old row is cancelled first and the new one is only written if that cancel
     * won, so an edit that arrives a moment too late leaves exactly one message in
     * existence and it is the one already on its way. Doing it the other way round
     * would send both. The id changes, which is the same contract the drafts endpoint
     * already has for the same underlying reason: what is queued is a snapshot, and a
     * new snapshot is a new thing.
     */
    public Outcome replace(String mailbox, Long id, Outgoing message, LocalDateTime sendAt,
                           String replyToEmailId) {
        String owner = normalise(mailbox);
        QueuedMessage existing = queue.findByIdAndMailbox(id, owner).orElse(null);
        if (existing == null) {
            return Outcome.refused(Problem.NOT_FOUND, "There is no such message in your outbox.", null);
        }

        // A message that was scheduled keeps its time when the edit does not name a new
        // one; one that was only held for the undo window gets a fresh window, because
        // the person is still standing in front of it.
        LocalDateTime when = sendAt != null ? sendAt
                : (QueuedMessage.SCHEDULED.equals(existing.getKind()) ? existing.getSendAt() : null);

        // An early no, only so that a message whose own time has just passed is refused
        // in the words that describe it rather than by the future-time rule below. The
        // decision is still the cancel, which is the only thing the sender loop races.
        if (!existing.cancellableAt(clock())) {
            return Outcome.refused(Problem.TOO_LATE, lateSentence(existing), existing);
        }

        // Checked before anything is cancelled. An edit that names an impossible time
        // has to leave the person with the message they already had.
        check(message, when, clock());

        Outcome stopped = cancel(owner, id, null);
        if (!stopped.ok()) return stopped;

        // The old message is gone at this point, so nothing after this line is allowed
        // to refuse. If the scheduled time went by in the moment between the check and
        // the cancel, the edit becomes an ordinary held send rather than an exception
        // that would leave the person with no message at all.
        LocalDateTime effective = when != null && when.isAfter(clock()) ? when : null;
        QueuedMessage fresh = queue(owner, message, effective, replyToEmailId);
        existing = queue.findByIdAndMailbox(id, owner).orElse(null);
        if (existing != null) {
            existing.setReplacedById(fresh.getId());
            queue.save(existing);
        }
        return Outcome.done("That message was updated and still goes at " + fresh.getSendAt() + ".", fresh);
    }

    /** Everything this mailbox still has in play, plus failures nobody has read yet. */
    public List<QueuedMessage> open(String mailbox) {
        return queue.findOpen(normalise(mailbox));
    }

    public long unseenFailures(String mailbox) {
        return queue.countUnseenFailures(normalise(mailbox));
    }

    /** Marks a failure as read so it stops being shown. Never touches anything else. */
    public Outcome acknowledge(String mailbox, Long id) {
        String owner = normalise(mailbox);
        QueuedMessage row = queue.findByIdAndMailbox(id, owner).orElse(null);
        if (row == null) {
            return Outcome.refused(Problem.NOT_FOUND, "There is no such message in your outbox.", null);
        }
        if (!QueuedMessage.FAILED.equals(row.getState())) {
            return Outcome.refused(Problem.TOO_LATE,
                    "Only a message that failed can be dismissed.", row);
        }
        row.setAcknowledgedAt(clock());
        queue.save(row);
        return Outcome.done("Dismissed.", row);
    }

    // ------------------------------------------------------------------ the loop

    /**
     * The background pass.
     *
     * Two seconds because the undo window is ten and a person watching a countdown
     * reach zero should not then wait a minute, and one bounded indexed query every
     * two seconds is cheaper than the folder count the open tab already runs every
     * forty-five. The initial delay keeps it out of the way of the test suite, which
     * boots this context and drives runDue itself.
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 2_000)
    public void tick() {
        if (!autostart) return;
        try {
            runDue(clock());
        } catch (RuntimeException e) {
            // A pass that throws must not kill the timer, because the next message due
            // is somebody's donor thank-you and this one may have failed on a row that
            // will be gone by the next pass anyway.
            System.err.println("Outbox pass failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /**
     * One pass over the due work. Returns how many messages were handed to the mail
     * server, which is what the tests count.
     *
     * Public and taking its own now so a test can run a single deterministic pass, and
     * so a pass can be run against a moment other than this instant without the clock
     * having to be mocked.
     */
    public int runDue(LocalDateTime instant) {
        LocalDateTime now = QueuedMessage.whole(instant);
        recoverStalled(now);

        List<QueuedMessage> due = queue.findDue(now, PageRequest.of(0, batchSize));
        int sent = 0;
        for (QueuedMessage row : due) {
            if (!credentials.knows(row.getMailbox())) {
                waitForUnlock(row, now);
                continue;
            }
            // The one gate. Everything past this line is happening exactly once.
            if (queue.claim(row.getId(), now) != 1) continue;
            if (deliver(row, now)) sent++;
        }
        return sent;
    }

    /**
     * Hands one claimed message to the mail server and records what came back.
     *
     * Not transactional, deliberately. The claim has already committed, and wrapping
     * the send in a transaction that also holds the settle would mean a rollback could
     * put a message that has genuinely been sent back into HELD, which is the one
     * outcome the claim exists to prevent.
     */
    private boolean deliver(QueuedMessage row, LocalDateTime now) {
        long started = System.currentTimeMillis();
        try {
            String emailId = mail.send(row.getMailbox(), row.toOutgoing());
            long elapsed = System.currentTimeMillis() - started;

            queue.settle(row.getId(), QueuedMessage.SENT, now, emailId, null);
            markParentAnswered(row);
            recordSent(row, emailId, elapsed);
            return true;
        } catch (RuntimeException e) {
            onFailure(row, e, now, System.currentTimeMillis() - started);
            return false;
        }
    }

    /**
     * What to do about a send that threw.
     *
     * Retried only when the failure provably happened before anything reached the mail
     * server. A refused connection, an unresolvable host and a connect timeout all
     * mean the request never left, so trying again later cannot duplicate anything.
     * Every other failure, a read timeout included, might have been a message Stalwart
     * accepted and an answer we never saw, and retrying that is how a donor gets two
     * copies of the same letter. Those fail once, loudly, and a person decides.
     */
    private void onFailure(QueuedMessage row, RuntimeException e, LocalDateTime now, long elapsed) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();

        if (neverLeft(e) && row.getAttempts() < maxAttempts) {
            LocalDateTime retryAt = QueuedMessage.whole(now.plusMinutes(backoffMinutes(row.getAttempts())));
            queue.release(row.getId(), retryAt,
                    "The mail server could not be reached at " + now + ". Trying again at " + retryAt + ".");
            return;
        }

        String sentence = neverLeft(e)
                ? "The mail server could not be reached after " + row.getAttempts()
                        + " attempts, so this message was not sent. Nothing left this server."
                : "The mail server refused this message: " + reason
                        + " Nothing was sent, and the message is still here.";

        queue.settle(row.getId(), QueuedMessage.FAILED, now, null, sentence);
        audit.record("MAIL_QUEUE_FAILED", row.getMailbox(), "outbox " + row.getId() + ", " + sentence);
        for (String recipient : row.everyRecipient()) {
            messageLog.recordFailed(recipient, row.getSubject(), null, sentence, elapsed, row.getMailbox());
        }
    }

    /**
     * A message whose mailbox nobody has open.
     *
     * It keeps its place in the queue and goes the moment somebody unlocks, because
     * that is almost always what has happened: the person who scheduled it signed out,
     * and the message is fine. The note is written only when it changes, so a message
     * waiting overnight is one row update rather than one every two seconds.
     */
    private void waitForUnlock(QueuedMessage row, LocalDateTime now) {
        if (row.getSendAt().plusHours(lockedGraceHours).isBefore(now)) {
            // Claimed first so the same guard that protects every other transition
            // protects this one, and so a person unlocking the mailbox at this exact
            // moment cannot have the message both sent and failed.
            if (queue.claim(row.getId(), now) == 1) {
                String sentence = "Nobody opened " + row.getMailbox() + " within " + lockedGraceHours
                        + " hours of when this was due, and this server cannot send from a mailbox "
                        + "that has not been unlocked, so it was not sent.";
                queue.settle(row.getId(), QueuedMessage.FAILED, now, null, sentence);
                audit.record("MAIL_QUEUE_FAILED", row.getMailbox(),
                        "outbox " + row.getId() + ", mailbox never unlocked");
            }
            return;
        }

        String waiting = "Waiting for " + row.getMailbox()
                + " to be unlocked on this server. It goes as soon as somebody opens it.";
        if (!waiting.equals(row.getLastError())) queue.note(row.getId(), waiting);
    }

    /**
     * Claims nobody came back from, which is what a restart in the middle of a send
     * leaves behind.
     *
     * These are not retried and they are not quietly dropped. Whether Stalwart accepted
     * the submission before the process died is genuinely unknowable from here, and
     * both guesses are bad in a way the person cannot repair afterwards, so the row
     * says exactly that and asks them to look in Sent. This is the one case where the
     * outbox admits it does not know.
     */
    private void recoverStalled(LocalDateTime now) {
        List<QueuedMessage> stalled =
                queue.findStalled(now.minusMinutes(stallMinutes), PageRequest.of(0, batchSize));
        for (QueuedMessage row : stalled) {
            String sentence = "This server stopped while this message was being handed to the mail "
                    + "server, so it may or may not have gone out. Check Sent before sending it again.";
            if (queue.settle(row.getId(), QueuedMessage.FAILED, now, null, sentence) == 1) {
                audit.record("MAIL_QUEUE_STALLED", row.getMailbox(),
                        "outbox " + row.getId() + " was claimed at " + row.getClaimedAt()
                                + " and never settled");
            }
        }
    }

    /**
     * The nightly tidy. Sent and cancelled rows are history the message log and the
     * audit log already hold in the shape those screens read, so this table only needs
     * to remember what is still in play.
     */
    @Scheduled(initialDelay = 300_000, fixedDelay = 86_400_000)
    public void purge() {
        if (!autostart) return;
        queue.purgeSettledBefore(QueuedMessage.whole(clock().minusDays(retentionDays)));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * The reply arrow on the parent, set after the reply has actually gone rather than
     * when it was queued, because a message that was cancelled inside its window was
     * never an answer to anything.
     */
    private void markParentAnswered(QueuedMessage row) {
        if (row.getReplyToEmailId() == null || row.getReplyToEmailId().isBlank()) return;
        try {
            mail.setKeyword(row.getMailbox(), row.getReplyToEmailId(), "answered", true);
        } catch (MailException e) {
            // A flag that did not stick is not a reason to call a delivered message
            // failed. The parent may also have been deleted while this waited.
        }
    }

    /**
     * One row per address, the same shape an immediate send writes, so that a search
     * for one message finds it whether it went straight away or sat here overnight.
     * Blind copies get a row like anybody else, because once the Sent copy carries no
     * Bcc header this log is the only surviving record of who it reached.
     */
    private void recordSent(QueuedMessage row, String emailId, long elapsed) {
        audit.record("MAIL_SENT", clip(row.getMailbox() + " to " + String.join(", ", row.getTo())),
                "outbox " + row.getId() + ", subject "
                        + (row.getSubject().isBlank() ? "(none)" : row.getSubject())
                        + (emailId == null ? "" : ", id " + emailId)
                        + (row.getBcc().isEmpty() ? "" : ", bcc " + String.join(", ", row.getBcc()))
                        + (row.getAttachments().isEmpty() ? "" : ", files " + names(row.getAttachments())));

        for (String recipient : row.everyRecipient()) {
            messageLog.record("OUTBOUND", row.getMailbox(), recipient,
                    row.getSubject().isBlank() ? "(no subject)" : row.getSubject(),
                    null, null, null, "SENT",
                    "Accepted by the mail server for delivery", elapsed, row.getMailbox());
        }
    }

    /**
     * Whether a failure happened before the request left this machine.
     *
     * Deliberately narrow. Only the three causes that cannot possibly have delivered
     * anything count, and everything else, including a timeout waiting for an answer,
     * is treated as an unknown that must never be retried automatically.
     */
    private static boolean neverLeft(RuntimeException e) {
        if (!(e instanceof MailException mail) || mail.getKind() != MailException.Kind.TRANSPORT) {
            return false;
        }
        Throwable cause = mail.getCause();
        return cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof HttpConnectTimeoutException;
    }

    /** One minute, then four, then nine. Slow enough to outlast a restart, quick enough to matter. */
    private static long backoffMinutes(int attempts) {
        long n = Math.max(1, attempts);
        return n * n;
    }

    private static String names(List<Attachment> files) {
        StringBuilder out = new StringBuilder();
        for (Attachment file : files) {
            if (out.length() > 0) out.append(", ");
            out.append(file.safeName());
        }
        return out.toString();
    }

    /**
     * What to tell somebody whose cancel arrived too late, in the words that are true
     * for the state the message is actually in.
     */
    private static String lateSentence(QueuedMessage row) {
        return switch (row.getState()) {
            case QueuedMessage.SENDING ->
                    "That message is being sent right now and can no longer be stopped.";
            case QueuedMessage.SENT ->
                    "That message has already been sent. Mail cannot be recalled once it has left "
                            + "this server, so the only thing left is to send a correction.";
            case QueuedMessage.CANCELLED -> "That message was already stopped.";
            case QueuedMessage.FAILED -> "That message failed and was never sent.";
            default ->
                    "That message is past the point where it could be stopped. Mail cannot be "
                            + "recalled once it has left this server.";
        };
    }

    private static String normalise(String mailbox) {
        return mailbox == null ? "" : mailbox.trim().toLowerCase(Locale.ROOT);
    }

    private static String clip(String value) {
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
    }

    /**
     * Whole seconds, at the boundary, for the same reason every stored timestamp is.
     * A now with a fraction on it compares differently against a truncated sendAt on
     * Postgres and on H2, and the comparison is the whole feature.
     */
    protected LocalDateTime clock() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
