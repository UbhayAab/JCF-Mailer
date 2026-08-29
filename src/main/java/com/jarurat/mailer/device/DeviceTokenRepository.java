package com.jarurat.mailer.device;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    Optional<DeviceToken> findBySelector(String selector);

    List<DeviceToken> findByFamilyId(String familyId);

    /**
     * What the devices screen shows. Only live rows, because a superseded row is a
     * spent token kept for replay detection and not a device somebody owns.
     */
    List<DeviceToken> findByMailboxAndSupersededAtIsNullOrderByLastSeenAtDesc(String mailbox);

    List<DeviceToken> findByMailbox(String mailbox);

    void deleteByFamilyId(String familyId);

    void deleteByMailbox(String mailbox);

    void deleteByExpiresAtBefore(Instant cutoff);

    /**
     * Claims a token for rotation, atomically, and answers 1 if this caller is the
     * one that got it.
     *
     * A phone waking up fires several requests at once with the same cookie, and
     * every one of them arrives with no session yet, so all of them try to rotate.
     * Read-then-write would let two of them both believe they had rotated, mint two
     * successors, and leave the browser holding whichever Set-Cookie landed last
     * while the other successor sat unused. The predicate on superseded_at is what
     * makes exactly one of them the winner: the second statement blocks on the row
     * until the first commits, then re-evaluates and matches nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DeviceToken t set t.supersededAt = :now where t.id = :id and t.supersededAt is null")
    int claimForRotation(@Param("id") Long id, @Param("now") Instant now);
}
