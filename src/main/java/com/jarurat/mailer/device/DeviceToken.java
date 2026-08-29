package com.jarurat.mailer.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One issued device token. A phone that stays signed in for months is a chain of
 * these rows, one per use, all carrying the same family id.
 *
 * There are two identifiers on purpose, and the split is what lets the lookup be a
 * plain indexed equality test without the token itself ever being stored. The
 * selector is a public handle: it comes back in the cookie, it names the row, and it
 * is worth nothing on its own. The secret half of the cookie is never here at all,
 * only its SHA-256, hashed the way ApiKeyHasher already hashes API keys, for the same
 * reason: 256 bits of SecureRandom output needs a fast digest and not a password
 * stretcher, and a digest is not reversible into a working cookie.
 *
 * The family is the device. Rotation writes a new row and marks the old one
 * superseded rather than deleting it, because a superseded row is the only evidence
 * that a token which has already been spent is being presented again. Keeping it
 * until it would have expired anyway is what makes replay detectable; deleting it on
 * rotation would turn a stolen cookie into an unknown cookie, which looks exactly
 * like an ordinary expired one and revokes nothing.
 *
 * The credential envelope is the mailbox password sealed under a key derived from the
 * cookie secret. DeviceCredentialCipher carries the threat model. Nothing in this row
 * can open it.
 */
@Entity
@Table(name = "device_token", indexes = {
        @Index(name = "idx_device_selector", columnList = "selector", unique = true),
        @Index(name = "idx_device_family", columnList = "familyId"),
        @Index(name = "idx_device_mailbox", columnList = "mailbox")
})
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The lookup handle from the cookie. Public by design, useless by itself. */
    @Column(nullable = false, unique = true, length = 64)
    private String selector;

    /** SHA-256 of the cookie's secret half. The secret itself is never stored. */
    @Column(nullable = false, length = 64)
    private String secretHash;

    /** Constant for the life of one device, so revocation can take the whole chain. */
    @Column(nullable = false, length = 64)
    private String familyId;

    /**
     * The mailbox this token is bound to, and deliberately not an app_user id. Most
     * of the people this feature is for have no app_user row at all, so binding to
     * one would exclude exactly the phones that complain.
     */
    @Column(nullable = false)
    private String mailbox;

    /**
     * The sealed mailbox password. Long enough for a password of any sane length
     * plus the version byte, salt, nonce and tag, and short enough that Hibernate
     * keeps it a varchar rather than promoting it to a large object.
     */
    @Column(length = 2000)
    private String credentialEnvelope;

    /** What to show a person on the devices list, from the user agent that enrolled. */
    @Column(length = 120)
    private String label;

    /** Carried forward across rotations, so the list can say when this device joined. */
    @Column(nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(nullable = false)
    private Instant lastSeenAt = Instant.now();

    /** Through ClientIp, which is the only header this application trusts for it. */
    @Column(length = 64)
    private String lastIp;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Set the moment this row is spent. A presentation after that is a replay. */
    private Instant supersededAt;

    protected DeviceToken() {
    }

    DeviceToken(String selector, String secretHash, String familyId, String mailbox,
                String credentialEnvelope, String label, Instant firstSeenAt,
                Instant lastSeenAt, String lastIp, Instant expiresAt) {
        this.selector = selector;
        this.secretHash = secretHash;
        this.familyId = familyId;
        this.mailbox = mailbox;
        this.credentialEnvelope = credentialEnvelope;
        this.label = label;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.lastIp = lastIp;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getSelector() { return selector; }
    public String getSecretHash() { return secretHash; }
    public String getFamilyId() { return familyId; }
    public String getMailbox() { return mailbox; }
    public String getCredentialEnvelope() { return credentialEnvelope; }
    public String getLabel() { return label; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public String getLastIp() { return lastIp; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getSupersededAt() { return supersededAt; }

    void setSupersededAt(Instant supersededAt) { this.supersededAt = supersededAt; }
    void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    void setLastIp(String lastIp) { this.lastIp = lastIp; }

    boolean isLive(Instant now) {
        return supersededAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
