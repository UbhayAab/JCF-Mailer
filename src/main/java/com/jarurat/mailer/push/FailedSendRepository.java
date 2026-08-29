package com.jarurat.mailer.push;

import com.jarurat.mailer.models.QueuedMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A read-only view of the outbox, for the one question this package asks of it.
 *
 * A second repository over QueuedMessage rather than a method added to
 * QueuedMessageRepository, because that file belongs to the outbox and this query
 * belongs to notifications, and because a repository that is declared read-only cannot
 * accidentally become the place somebody settles a row from.
 */
public interface FailedSendRepository extends Repository<QueuedMessage, Long> {

    /**
     * Sends that have failed since the last time this was asked, oldest first.
     *
     * Bounded by a Pageable for the same reason OutboxService bounds its own sweep: a
     * mail server refusing everything for an hour produces a very large answer to this
     * question, and a notification storm is a worse outcome than a delayed one.
     */
    @Query("""
            select q from QueuedMessage q
            where q.state = 'FAILED' and q.acknowledgedAt is null and q.settledAt > :since
            order by q.settledAt asc, q.id asc
            """)
    List<QueuedMessage> failedSince(@Param("since") LocalDateTime since, Pageable page);
}
