package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.QueuedMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The outbox, and the guarded statements that make it safe.
 *
 * Every state change on a queued message is one UPDATE with the current state in its
 * WHERE clause, and the caller reads the row count to learn whether it won. There is
 * no load, decide, save anywhere in this feature, and that is the whole design rather
 * than a stylistic preference. JourneyParticipant uses the other pattern, an @Version
 * column and an entity write, and that is right for it because a participant is
 * advanced by one loop and read by nobody else. A queued message is different: a
 * person pressing Undo and a sender loop reaching for the same row are genuinely
 * simultaneous, and with load, decide, save the loser of that race finds out one
 * statement too late, after it has already decided. Here the database decides, once,
 * and both callers are told the truth by the same number.
 *
 * Because the state column is the lock, there is deliberately no @Version on the
 * entity. Mixing the two would be worse than either: a bulk UPDATE does not raise the
 * version, so an entity write holding a stale version would happily overwrite a claim
 * the sender loop had already made and turn a message that was on the wire into a row
 * marked cancelled.
 */
public interface QueuedMessageRepository extends JpaRepository<QueuedMessage, Long> {

    /**
     * Work that is due, oldest first so a backlog cannot starve the message that has
     * been waiting longest.
     *
     * Rows come back whole rather than as ids because the loop needs the mailbox
     * before it decides whether to claim: a mailbox nobody has unlocked in this
     * process has no secret to send with, and claiming a row we cannot send is how a
     * message ends up marked SENDING with nothing behind it.
     */
    @Query("""
            select q from QueuedMessage q
            where q.state = 'HELD' and q.sendAt <= :now
            order by q.sendAt asc, q.id asc
            """)
    List<QueuedMessage> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Takes ownership of one message, and answers 1 exactly once however many callers
     * ask.
     *
     * This is the statement that makes a double send impossible. Two application
     * instances, or one instance whose previous tick overran, both run this against
     * the same id; the database serialises them on the row, the first turns HELD into
     * SENDING and reports one row changed, and the second finds no row in HELD and
     * reports zero. Only a caller holding the 1 is allowed to hand anything to the
     * mail server. The count is also why the claim commits before the send starts
     * rather than sharing a transaction with it, which is the ordering that matters
     * if the process dies halfway.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QueuedMessage q
            set q.state = 'SENDING', q.claimedAt = :now, q.attempts = q.attempts + 1
            where q.id = :id and q.state = 'HELD'
            """)
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Cancels a message, and refuses in exactly the cases the screen has already
     * promised it would.
     *
     * The mailbox is in the WHERE clause rather than checked beforehand, so a request
     * naming somebody else's message is indistinguishable from a request naming a
     * message that is already gone: both are zero rows and neither confirms that the
     * id exists. The sendAt test is the undo window and the schedule deadline at once,
     * and it is deliberately stricter than the state test alone. A row can sit in HELD
     * for a second or two past its time while the loop gets to it, and cancelling in
     * that gap would work; it is refused anyway, because a control that succeeds or
     * fails depending on how busy a background loop is cannot be explained to anyone.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QueuedMessage q
            set q.state = 'CANCELLED', q.settledAt = :now, q.replacedById = :replacedBy
            where q.id = :id and q.mailbox = :mailbox and q.state = 'HELD' and q.sendAt > :now
            """)
    int cancel(@Param("id") Long id,
               @Param("mailbox") String mailbox,
               @Param("now") LocalDateTime now,
               @Param("replacedBy") Long replacedBy);

    /**
     * Records what happened to a message the loop had claimed.
     *
     * Guarded on SENDING for the same reason the claim is guarded on HELD. Nothing but
     * the holder of a claim may settle a row, so a stale worker coming back from a long
     * pause cannot overwrite the outcome a recovery pass has already written.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QueuedMessage q
            set q.state = :state, q.settledAt = :now, q.sentEmailId = :sentEmailId,
                q.lastError = :error
            where q.id = :id and q.state = 'SENDING'
            """)
    int settle(@Param("id") Long id,
               @Param("state") String state,
               @Param("now") LocalDateTime now,
               @Param("sentEmailId") String sentEmailId,
               @Param("error") String error);

    /**
     * Puts a claimed message back in the queue for a later attempt.
     *
     * Only ever called for a failure that provably happened before anything reached
     * the mail server, which OutboxService decides and this statement takes on trust.
     * Guarded on SENDING so it can only ever undo a claim this same pass made.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QueuedMessage q
            set q.state = 'HELD', q.sendAt = :retryAt, q.claimedAt = null, q.lastError = :error
            where q.id = :id and q.state = 'SENDING'
            """)
    int release(@Param("id") Long id,
                @Param("retryAt") LocalDateTime retryAt,
                @Param("error") String error);

    /**
     * Notes on a held message why it has not gone yet without disturbing its place in
     * the queue, which is what a locked mailbox gets.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QueuedMessage q set q.lastError = :error
            where q.id = :id and q.state = 'HELD'
            """)
    int note(@Param("id") Long id, @Param("error") String error);

    /**
     * Claims that never came back, which is what a restart in the middle of a send
     * leaves behind.
     */
    @Query("""
            select q from QueuedMessage q
            where q.state = 'SENDING' and q.claimedAt is not null and q.claimedAt <= :before
            order by q.claimedAt asc
            """)
    List<QueuedMessage> findStalled(@Param("before") LocalDateTime before, Pageable pageable);

    /** One message, and only if it belongs to the mailbox asking. */
    Optional<QueuedMessage> findByIdAndMailbox(Long id, String mailbox);

    /**
     * What the outbox screen shows: everything still waiting, then everything that
     * failed and has not been read yet. Ordered by when it should have gone.
     */
    @Query("""
            select q from QueuedMessage q
            where q.mailbox = :mailbox
              and (q.state in ('HELD', 'SENDING')
                   or (q.state = 'FAILED' and q.acknowledgedAt is null))
            order by q.sendAt asc, q.id asc
            """)
    List<QueuedMessage> findOpen(@Param("mailbox") String mailbox);

    /**
     * How many failures this mailbox has not seen. The mail screen already polls, so
     * this is the number that turns a 3am failure into something visible in the
     * morning without anybody going looking for it.
     */
    @Query("""
            select count(q) from QueuedMessage q
            where q.mailbox = :mailbox and q.state = 'FAILED' and q.acknowledgedAt is null
            """)
    long countUnseenFailures(@Param("mailbox") String mailbox);

    long countByMailboxAndState(String mailbox, String state);

    /**
     * Settled rows older than a cutoff, so the table does not grow without limit. Sent
     * and cancelled messages are history the message log already holds properly; this
     * one only needs to remember what is still in play.
     */
    @Transactional
    @Modifying
    @Query("""
            delete from QueuedMessage q
            where q.state in ('SENT', 'CANCELLED')
              and q.settledAt is not null and q.settledAt < :before
            """)
    int purgeSettledBefore(@Param("before") LocalDateTime before);
}
