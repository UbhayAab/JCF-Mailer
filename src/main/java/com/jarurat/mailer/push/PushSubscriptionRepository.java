package com.jarurat.mailer.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every query here is keyed on the mailbox, and that is the isolation boundary.
 *
 * No endpoint in this application accepts a mailbox as a parameter; it always comes
 * from MailboxAccess, which reads it off the session pin. A finder that took only a
 * device id would quietly undo that, because a device id is chosen by the browser and
 * a person can put any string they like in it, so the pair is always looked up
 * together and never the id alone.
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionRecord, Long> {

    List<PushSubscriptionRecord> findByMailbox(String mailbox);

    Optional<PushSubscriptionRecord> findByMailboxAndDeviceId(String mailbox, String deviceId);

    long countByMailbox(String mailbox);

    @Transactional
    @Modifying
    long deleteByMailboxAndDeviceId(String mailbox, String deviceId);

    @Transactional
    @Modifying
    long deleteByMailbox(String mailbox);

    /**
     * Rows whose registration at the mail server is close enough to lapsing to be
     * worth renewing. Stalwart clamps a PushSubscription to seven days, so this is
     * asked daily and the window is generous on purpose: a renewal needs the mailbox
     * credential, which only exists while somebody has the mailbox open, so there has
     * to be more than one chance to catch it.
     */
    @Query("""
            select s from PushSubscriptionRecord s
            where s.jmapSubscriptionId is not null and s.jmapExpiresAt < :before
            order by s.jmapExpiresAt asc
            """)
    List<PushSubscriptionRecord> dueForRenewal(@Param("before") Instant before);

    /**
     * Rows that have been failing for long enough to be dead without ever having said
     * so with a 404 or a 410.
     *
     * Those two status codes are the honest signal and pruning on them is exact, but
     * not every push service sends one: some answer 400 forever for an endpoint whose
     * browser profile was deleted. Without this the table only ever grows, and it
     * grows fastest on the devices that are least likely to come back.
     */
    @Query("""
            select s from PushSubscriptionRecord s
            where s.failureCount >= :failures and s.lastFailureAt < :before
              and (s.lastSuccessAt is null or s.lastSuccessAt < :before)
            """)
    List<PushSubscriptionRecord> deadWeight(@Param("failures") int failures,
                                            @Param("before") Instant before);
}
