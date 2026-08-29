package com.jarurat.mailer.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * One browser installation that has agreed to receive notifications for one mailbox.
 *
 * THE KEY IS THE MAILBOX ADDRESS AND A DEVICE ID, AND NEITHER HALF IS OPTIONAL
 * ------------------------------------------------------------------------------
 * The mailbox rather than an app_user id, for the reason MailboxSettings already
 * spells out: somebody who signed in through MailboxAuthenticationProvider has proved
 * a Stalwart password and nothing else, so there is no app_user row to hang this off,
 * and a foreign key onto one would either refuse the insert or file everybody's phone
 * under whichever console account happened to be signed in. The device id as well as
 * the mailbox, because one person has a phone and a laptop and both want telling; a
 * row keyed on the address alone would let the laptop overwrite the phone and the
 * phone would stop notifying with nothing to show for it.
 *
 * The consequence, stated plainly because it is the same one MailboxSettings carries:
 * these are shared mailboxes, so a notification for support@ goes to every device any
 * of the three people sharing it has registered. That is correct, since the mail is
 * addressed to the alias and not to a person, and it is the reason the notification
 * design puts almost everything in a silent lane.
 *
 * WHAT IS AND IS NOT A SECRET IN HERE
 * ------------------------------------------------------------------------------
 * No mailbox password is stored, and that is the whole point of doing it this way: a
 * database backup of this table buys an attacker nothing that lets them read mail. The
 * endpoint is different. It is a bearer capability, so anyone holding it can make that
 * device show a notification, and it must never be written to a log or an audit row.
 * endpointHash exists for exactly that: it is what gets logged and reported when a
 * subscription has to be named. The p256dh and auth values are the browser's half of
 * the encryption and are useless without the endpoint, but they are kept beside it and
 * treated with the same care.
 */
@Entity
@Table(name = "push_subscription",
        uniqueConstraints = @UniqueConstraint(name = "uq_push_mailbox_device",
                columnNames = {"mailbox", "device_id"}),
        indexes = {
                @Index(name = "idx_push_mailbox", columnList = "mailbox"),
                @Index(name = "idx_push_jmap_expires", columnList = "jmap_expires_at")
        })
public class PushSubscriptionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String mailbox;

    /** Chosen by the browser, stable across reloads, opaque to us. */
    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(nullable = false, length = 2048)
    private String endpoint;

    /** SHA-256 of the endpoint, hex. The only form of it that may appear in a log. */
    @Column(name = "endpoint_hash", nullable = false, length = 64)
    private String endpointHash;

    /** The subscription's P-256 public key, base64url, 65 bytes decoded. */
    @Column(name = "ua_public", nullable = false, length = 200)
    private String uaPublic;

    /** The subscription's auth secret, base64url, 16 bytes decoded. */
    @Column(name = "auth_secret", nullable = false, length = 64)
    private String authSecret;

    /**
     * What Stalwart calls this same subscription, once it has been registered there so
     * that new mail can be pushed while nothing of ours is running. Null when Stalwart
     * does not support push or the registration failed; push from this application
     * still works in that case, which is why it is nullable rather than required.
     */
    @Column(name = "jmap_subscription_id", length = 128)
    private String jmapSubscriptionId;

    /**
     * When Stalwart will stop pushing to this device. Stalwart hard clamps the
     * lifetime of a PushSubscription to seven days, so this is never more than a week
     * out and the renewal task exists entirely because of it.
     */
    @Column(name = "jmap_expires_at")
    private Instant jmapExpiresAt;

    @Column(name = "jmap_verified", nullable = false)
    private boolean jmapVerified;

    /**
     * Whether the mail server took the emailPush extension, which is what puts the
     * sender and subject inside the encrypted payload rather than a bare state change.
     * Recorded rather than assumed, because a server without it degrades to a
     * notification that says only that something arrived, and the screen should say so
     * instead of promising a preview that will never come.
     */
    @Column(name = "email_push", nullable = false)
    private boolean emailPush;

    /**
     * Whether a push from this server has ever actually arrived at this device.
     *
     * It is the only honest evidence there is. Everything else in this row proves that
     * a push service accepted a message for delivery, which is not the same claim: a
     * subscription can be accepted, signed, encrypted and queued and still never reach
     * a phone, because the browser was uninstalled or the user revoked permission in a
     * way the push service has not noticed yet. Nothing may relax the polling fallback
     * until this is true.
     */
    @Column(name = "push_seen", nullable = false)
    private boolean pushSeen;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    /** The last HTTP status the push service gave us, so a person can be told which. */
    @Column(name = "last_status")
    private Integer lastStatus;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    /**
     * Set from a 429 Retry-After. Nothing is sent to this endpoint until it passes.
     *
     * Honouring it is not politeness. A push service that is rate limiting and gets
     * hammered anyway starts rejecting for longer, and on a shared endpoint host that
     * penalty is not scoped to the one subscription that earned it.
     */
    @Column(name = "retry_after")
    private Instant retryAfter;

    protected PushSubscriptionRecord() {}

    public PushSubscriptionRecord(String mailbox, String deviceId, String endpoint,
                                  String uaPublic, String authSecret) {
        this.mailbox = normalise(mailbox);
        this.deviceId = deviceId;
        this.endpoint = endpoint;
        this.endpointHash = hash(endpoint);
        this.uaPublic = uaPublic;
        this.authSecret = authSecret;
        this.createdAt = Instant.now();
    }

    /** Same device, new endpoint: the browser rotated it, which they do routinely. */
    public void repoint(String newEndpoint, String newUaPublic, String newAuthSecret) {
        this.endpoint = newEndpoint;
        this.endpointHash = hash(newEndpoint);
        this.uaPublic = newUaPublic;
        this.authSecret = newAuthSecret;
        this.failureCount = 0;
        this.lastStatus = null;
        this.lastError = null;
        this.retryAfter = null;
        this.pushSeen = false;
    }

    public void recordSuccess(Instant when) {
        this.lastSuccessAt = when;
        this.failureCount = 0;
        this.lastStatus = 201;
        this.lastError = null;
        this.retryAfter = null;
    }

    public void recordFailure(Instant when, int status, String message, Instant backOffUntil) {
        this.lastFailureAt = when;
        this.lastStatus = status;
        this.lastError = message == null ? null : message.substring(0, Math.min(500, message.length()));
        this.failureCount++;
        if (backOffUntil != null) this.retryAfter = backOffUntil;
    }

    public boolean sendableAt(Instant now) {
        return retryAfter == null || !now.isBefore(retryAfter);
    }

    /** Hex SHA-256. Short enough to read in a report, useless as a capability. */
    public static String hash(String endpoint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("This JVM has no SHA-256", e);
        }
    }

    private static String normalise(String mailbox) {
        return mailbox == null ? "" : mailbox.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() { return id; }
    public String getMailbox() { return mailbox; }
    public String getDeviceId() { return deviceId; }
    public String getEndpoint() { return endpoint; }
    public String getEndpointHash() { return endpointHash; }
    public String getUaPublic() { return uaPublic; }
    public String getAuthSecret() { return authSecret; }
    public String getJmapSubscriptionId() { return jmapSubscriptionId; }
    public Instant getJmapExpiresAt() { return jmapExpiresAt; }
    public boolean isJmapVerified() { return jmapVerified; }
    public boolean isEmailPush() { return emailPush; }
    public boolean isPushSeen() { return pushSeen; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public Integer getLastStatus() { return lastStatus; }
    public String getLastError() { return lastError; }
    public int getFailureCount() { return failureCount; }
    public Instant getRetryAfter() { return retryAfter; }

    public void setJmapSubscriptionId(String value) { this.jmapSubscriptionId = value; }
    public void setJmapExpiresAt(Instant value) { this.jmapExpiresAt = value; }
    public void setJmapVerified(boolean value) { this.jmapVerified = value; }
    public void setEmailPush(boolean value) { this.emailPush = value; }
    public void setPushSeen(boolean value) { this.pushSeen = value; }
}
