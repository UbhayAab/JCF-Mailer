package com.jarurat.mailer.push;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the row and its three collections survive a round trip, and that two mailboxes
 * stay two rows.
 *
 * The unit tests prove the rule and none of them touches a database, so the thing most
 * likely to go wrong here is the mapping rather than the logic: the folder levels, the
 * VIP list and the mute ledger are three element collections joined on the mailbox
 * address, and any of them coming back empty would silently reset everybody to the
 * defaults on the next restart. A person would then find their VIPs gone and no error
 * anywhere, which is exactly the shape of failure that gets a feature switched off.
 *
 * It also pins the trap this file exists to avoid. The key is the mailbox address, not an
 * app_user id, because a mail-only session has no app_user row at all, so the second test
 * is the one that would fail the day somebody "tidies up" the key.
 */
@DataJpaTest
class NotificationRulesPersistenceTest {

    private static final Instant TUESDAY = Instant.parse("2026-09-08T05:34:00Z");

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("levels, VIPs and mutes all come back off the database")
    void theCollectionsRoundTrip() {
        NotificationRules rules = new NotificationRules("support@jarurat.care");
        rules.setLevel("inbox", NotificationRules.LEVEL_EVERYTHING);
        rules.setLevel("archive", NotificationRules.LEVEL_VIP);
        rules.setVips(Map.of("anand.mehta@tmc.gov.in", true, "@partner.example", false));
        rules.mute("chatty@example.org", TUESDAY);
        rules.setQuietHours(22, 7);
        rules.setUpdatedAt(TUESDAY);
        em.persist(rules);
        em.flush();
        em.clear();

        NotificationRules back = em.find(NotificationRules.class, "support@jarurat.care");

        assertThat(back.levelFor("inbox")).isEqualTo(NotificationRules.LEVEL_EVERYTHING);
        assertThat(back.levelFor("archive")).isEqualTo(NotificationRules.LEVEL_VIP);
        assertThat(back.vipList()).containsExactly("@partner.example", "anand.mehta@tmc.gov.in");
        assertThat(back.breaksThroughQuiet("anand.mehta@tmc.gov.in")).isTrue();
        assertThat(back.breaksThroughQuiet("someone@partner.example")).isFalse();
        assertThat(back.isMuted("chatty@example.org", TUESDAY)).isTrue();
        assertThat(back.getQuietStartHour()).isEqualTo(22);
        assertThat(back.getQuietEndHour()).isEqualTo(7);
    }

    @Test
    @DisplayName("two mailboxes are two rows, and one person's VIPs are not the other's")
    void mailboxesAreIsolated() {
        NotificationRules support = new NotificationRules("support@jarurat.care");
        support.setVips(Map.of("@tmc.gov.in", false));
        support.setLevel("inbox", NotificationRules.LEVEL_EVERYTHING);

        NotificationRules priya = new NotificationRules("priya@jarurat.care");
        priya.setQuietEnabled(false);

        em.persist(support);
        em.persist(priya);
        em.flush();
        em.clear();

        NotificationRules storedSupport = em.find(NotificationRules.class, "support@jarurat.care");
        NotificationRules storedPriya = em.find(NotificationRules.class, "priya@jarurat.care");

        assertThat(storedSupport.isVip("anand.mehta@tmc.gov.in")).isTrue();
        assertThat(storedPriya.isVip("anand.mehta@tmc.gov.in")).isFalse();
        assertThat(storedPriya.levelFor("inbox")).isEqualTo(NotificationRules.LEVEL_DIRECT);
        assertThat(storedSupport.isQuietEnabled()).isTrue();
        assertThat(storedPriya.isQuietEnabled()).isFalse();
    }

    @Test
    @DisplayName("the address is normalised on the way in, so case cannot make a second row")
    void theKeyIsNormalised() {
        em.persist(new NotificationRules("  Support@Jarurat.Care "));
        em.flush();
        em.clear();

        assertThat(em.find(NotificationRules.class, "support@jarurat.care")).isNotNull();
    }
}
