package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.MailboxSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Preferences by mailbox address.
 *
 * The id type is String and it is the address, so findById is the isolation boundary
 * and there is deliberately no findAll-shaped read on the API path. A settings
 * endpoint that could be handed a mailbox would be the one place in webmail where the
 * mailbox is a parameter, which is exactly what MailboxAccess exists to prevent, so
 * every caller resolves the address from the session first and only then arrives here.
 */
public interface MailboxSettingsRepository extends JpaRepository<MailboxSettings, String> {

    /**
     * Every mailbox with an out of office switched on.
     *
     * Nothing on the request path uses this. It is here for the delivery side of the
     * responder, which has to ask the question the other way round: given a message
     * that has just arrived, is this mailbox answering. Written as a derived query so
     * Spring Data validates the property name at startup rather than at the first call.
     */
    List<MailboxSettings> findByVacationEnabledTrue();
}
