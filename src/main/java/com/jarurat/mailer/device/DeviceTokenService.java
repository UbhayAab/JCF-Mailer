package com.jarurat.mailer.device;

import com.jarurat.mailer.security.ApiKeyHasher;
import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates, revokes and reads back the device tokens. Everything that decides
 * whether a months-old cookie still opens a mailbox is in this one class.
 *
 * ROTATION AND REPLAY, which is the part of remember-me that is usually got wrong and
 * the reason this is not thirty lines.
 *
 * A token is spent the moment it is used. Every restore mints a successor, re-seals
 * the mailbox password under a key derived from the new secret, marks the presented
 * row superseded and sends the new cookie back. So a cookie copied off a phone this
 * morning stops matching a live row as soon as the phone itself makes one request.
 *
 * That is only half of it, and the missing half is what makes theft detectable rather
 * than merely inconvenient. When a token that has ALREADY been spent is presented, one
 * of two things has happened: the browser and a copy of the browser are both using
 * this device's chain, or somebody replayed a stolen cookie. There is no way to tell
 * which from here, and the safe reading is theft, so the response is to revoke the
 * WHOLE FAMILY and not just the token that was replayed. Revoking only the replayed
 * token would leave the thief's successor working, since the thief rotated too, and
 * would sign the real owner out while the attacker stayed in. This is the case that
 * gets left out, and it is precisely the case that matters.
 *
 * The grace window is the one concession, and it is deliberate rather than a
 * loosening. A phone waking up opens several requests at once, all with the same
 * cookie and none with a session yet, and a retried request after a dropped
 * connection does the same thing. Without a window, the ordinary act of unlocking a
 * phone would look like a replay and the device would revoke itself, which would make
 * this feature worse than the eight hour session it replaces. So a token superseded
 * within the last jarurat.device.rotation-grace-seconds is still honoured and simply
 * mints another successor in the same family. What that costs is bounded and worth
 * stating: an attacker who replays a stolen cookie inside that window gets a working
 * token instead of being detected. Outside it, which is every replay that is not
 * within a minute of the owner's own request, the family dies and both parties are
 * signed out, which is the correct end state because the owner can sign in again with
 * a password and the thief cannot.
 *
 * WHAT A RESTORED SESSION IS WORTH. The token is bound to a mailbox address and it
 * restores Role.MAILBOX, whether or not an app_user row exists for that address. That
 * is narrower than the session the person had when the token was issued, on purpose:
 * a cookie that lives for months must not be worth more than the mailbox it was minted
 * to carry, and Campaign Studio, the subscriber base and the team screen are not
 * things anybody should reach without typing a password since the last time the
 * clocks changed. An owner who wants the console signs in the way they always did.
 */
@Service
public class DeviceTokenService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);

    private final DeviceTokenRepository repository;
    private final DeviceSettings settings;
    private final AuditService audit;

    public DeviceTokenService(DeviceTokenRepository repository, DeviceSettings settings, AuditService audit) {
        this.repository = repository;
        this.settings = settings;
        this.audit = audit;
    }

    /** What a successful restore hands back: who, and the secret that opens their mail. */
    public record Restored(String mailbox, String mailboxSecret) {
    }

    /**
     * Turns a presented cookie into a mailbox and its password, rotating the token on
     * the way through, or answers empty and leaves the caller to show a login page.
     *
     * Empty is the answer for every failure, and the caller must not try to tell them
     * apart. The cookie is cleared here for the failures that mean this browser is
     * holding something that will never work again, so the next request does not
     * repeat the lookup, and left alone for the ones that might be transient.
     */
    @Transactional
    public Optional<Restored> restore(DeviceCookie.Presented presented, String ip,
                                      HttpServletResponse response) {
        if (!settings.isEnabled() || presented == null) return Optional.empty();

        Instant now = Instant.now();
        Optional<DeviceToken> found = repository.findBySelector(presented.selector());
        if (found.isEmpty()) {
            // A revoked device, a row that aged out, or a cookie from a database that
            // has been restored from a backup. Nothing to revoke and nothing to report.
            DeviceCookie.clear(response, settings.isCookieSecure());
            return Optional.empty();
        }

        DeviceToken token = found.get();
        if (!secretMatches(presented.secret(), token.getSecretHash())) {
            // The selector named a real row and the secret did not match it. That is a
            // guess at somebody else's token rather than a replay of a spent one, so the
            // family is left alone: revoking on this path would let anybody who could
            // read a selector sign a colleague's phone out at will.
            DeviceCookie.clear(response, settings.isCookieSecure());
            return Optional.empty();
        }

        // Read out before anything is written. claimForRotation clears the persistence
        // context so that a concurrent update cannot be masked by a stale first level
        // cache, which leaves this entity detached, and reading it afterwards is the
        // kind of thing that works until the day it does not.
        String family = token.getFamilyId();
        String mailbox = token.getMailbox();
        String label = token.getLabel();
        Instant firstSeenAt = token.getFirstSeenAt();
        String envelope = token.getCredentialEnvelope();
        Long rowId = token.getId();

        if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(now)) {
            repository.deleteByFamilyId(family);
            DeviceCookie.clear(response, settings.isCookieSecure());
            return Optional.empty();
        }

        Instant superseded = token.getSupersededAt();
        if (superseded != null && superseded.plus(settings.getRotationGrace()).isBefore(now)) {
            return replayed(family, mailbox, response);
        }

        // Decrypted before anything is written, because rotation has to re-seal this
        // same plaintext under the successor's key and there is no second copy of it
        // anywhere to fall back on.
        Optional<String> credential =
                DeviceCredentialCipher.open(presented.secret(), mailbox, envelope);
        if (credential.isEmpty()) {
            // The row is intact, the secret hashed correctly, and the envelope still
            // will not open. Either the column was tampered with or it was written
            // against a different mailbox, and both mean this chain is not trustworthy.
            log.warn("Device token for {} hashed correctly but its credential would not open; revoking the family.",
                    mailbox);
            repository.deleteByFamilyId(family);
            DeviceCookie.clear(response, settings.isCookieSecure());
            return Optional.empty();
        }

        // The claim is what makes exactly one of several simultaneous requests the
        // rotator. Losing it is not an error: it means a sibling request superseded
        // this row microseconds ago, which is the grace case arriving by another route,
        // so this request goes on to mint its own successor in the same family.
        if (superseded == null) {
            repository.claimForRotation(rowId, now);
        }

        issue(family, mailbox, credential.get(), label, firstSeenAt, ip, now, response);
        return Optional.of(new Restored(mailbox, credential.get()));
    }

    /**
     * Enrols this browser, replacing whatever token it was already carrying.
     *
     * Replacing rather than keeping is what makes a password change take effect. The
     * envelope holds the mailbox password as it was on the day it was sealed, so a
     * person who changes their password and signs in again would otherwise keep a
     * device that restores the old one and fails at Stalwart every morning until they
     * noticed.
     */
    @Transactional
    public void enrol(DeviceCookie.Presented presented, String mailbox, String mailboxSecret,
                      String label, String ip, HttpServletResponse response) {
        if (!settings.isEnabled()) return;
        String address = canonical(mailbox);
        if (address.isEmpty() || mailboxSecret == null || mailboxSecret.isEmpty()) return;

        if (presented != null) {
            repository.findBySelector(presented.selector())
                    .ifPresent(existing -> repository.deleteByFamilyId(existing.getFamilyId()));
        }

        // Enrolment happens once per sign in, so it is the right place to do the
        // housekeeping the table would otherwise never get. Rows that have expired
        // cannot authenticate anything and cannot detect a replay either, since the
        // expiry test runs first, so keeping them buys nothing.
        repository.deleteByExpiresAtBefore(Instant.now());
        pruneOldestBeyondLimit(address);

        Instant now = Instant.now();
        issue(UUID.randomUUID().toString(), address, mailboxSecret, label, now, ip, now, response);
        audit.record("DEVICE_ENROLLED", address, label);
    }

    /**
     * Sign out. The row goes, and with it the only copy of the sealed credential, so
     * this is the same promise MailboxAccess.close makes about the heap extended to
     * the copy at rest.
     *
     * Takes the request rather than a parsed cookie because SecurityConfig calls this
     * from a logout handler, and the cookie format is this package's business and not
     * something the security configuration should have to know how to read.
     */
    @Transactional
    public void forget(HttpServletRequest request, HttpServletResponse response) {
        DeviceCookie.Presented presented = DeviceCookie.parse(request).orElse(null);
        if (presented != null) {
            repository.findBySelector(presented.selector()).ifPresent(token -> {
                repository.deleteByFamilyId(token.getFamilyId());
                audit.record("DEVICE_REVOKED", token.getMailbox(), "signed out on " + token.getLabel());
            });
        }
        DeviceCookie.clear(response, settings.isCookieSecure());
    }

    /**
     * The devices belonging to one mailbox, newest use first, with the one making this
     * request marked.
     *
     * Collapsed by family rather than listed by row. During the grace window a family
     * legitimately holds more than one live row, and a list that showed the same phone
     * three times would make the one thing this screen is for, spotting a device that
     * is not yours, harder rather than easier.
     */
    @Transactional(readOnly = true)
    public List<DeviceSummary> list(String mailbox, DeviceCookie.Presented presented) {
        String address = canonical(mailbox);
        if (address.isEmpty()) return List.of();

        String currentFamily = presented == null ? null
                : repository.findBySelector(presented.selector())
                        .map(DeviceToken::getFamilyId).orElse(null);

        Instant now = Instant.now();
        Map<String, DeviceSummary> byFamily = new LinkedHashMap<>();
        for (DeviceToken token : repository.findByMailboxAndSupersededAtIsNullOrderByLastSeenAtDesc(address)) {
            if (!token.isLive(now)) continue;
            byFamily.putIfAbsent(token.getFamilyId(), new DeviceSummary(
                    token.getFamilyId(),
                    token.getLabel(),
                    token.getFirstSeenAt(),
                    token.getLastSeenAt(),
                    token.getLastIp(),
                    token.getFamilyId().equals(currentFamily)));
        }
        return List.copyOf(byFamily.values());
    }

    /**
     * Revokes one device, and only if it belongs to the mailbox asking.
     *
     * The mailbox test is the authorization, not a sanity check. The family id is on a
     * screen and therefore in a URL, and without this test anybody signed in anywhere
     * could sign any phone in the organisation out by pasting one.
     */
    @Transactional
    public boolean revoke(String mailbox, String familyId) {
        String address = canonical(mailbox);
        List<DeviceToken> family = repository.findByFamilyId(familyId);
        if (family.isEmpty()) return false;
        if (!family.stream().allMatch(token -> canonical(token.getMailbox()).equals(address))) return false;

        repository.deleteByFamilyId(familyId);
        audit.record("DEVICE_REVOKED", address, family.get(0).getLabel());
        return true;
    }

    /** The panic button: every device for this mailbox, including the one asking. */
    @Transactional
    public int revokeAll(String mailbox) {
        String address = canonical(mailbox);
        int devices = list(address, null).size();
        repository.deleteByMailbox(address);
        audit.record("DEVICE_REVOKED_ALL", address, devices + " devices");
        return devices;
    }

    /**
     * A spent token was presented outside the grace window. Somebody is holding a copy
     * of this device's cookie, and there is no way to tell from here whether the copy
     * or the original is in front of us.
     */
    private Optional<Restored> replayed(String family, String mailbox, HttpServletResponse response) {
        log.warn("Device token replay for {}: a token that had already been rotated was presented again. "
                + "Revoking the whole device family.", mailbox);
        audit.record("DEVICE_TOKEN_REPLAY", mailbox,
                "a rotated token was presented again; the device family was revoked");
        repository.deleteByFamilyId(family);
        DeviceCookie.clear(response, settings.isCookieSecure());
        return Optional.empty();
    }

    /**
     * Writes one new row and the cookie that opens it. Every path that hands a browser
     * a token comes through here, so there is exactly one place where the secret
     * exists in plaintext and it never leaves this method.
     */
    private void issue(String family, String mailbox, String credential, String label,
                       Instant firstSeenAt, String ip, Instant now, HttpServletResponse response) {
        String selector = DeviceCookie.mintSelector();
        String secret = DeviceCookie.mintSecret();

        repository.save(new DeviceToken(
                selector,
                ApiKeyHasher.sha256(secret),
                family,
                canonical(mailbox),
                DeviceCredentialCipher.seal(secret, mailbox, credential),
                label,
                firstSeenAt == null ? now : firstSeenAt,
                now,
                ip,
                now.plus(settings.getValidity())));

        DeviceCookie.write(response, DeviceCookie.valueOf(selector, secret),
                settings.getValidity(), settings.isCookieSecure());
    }

    /**
     * Keeps one mailbox to a bounded number of devices, oldest use first.
     *
     * These are shared mailboxes with several people in them, so the limit is not
     * about tidiness. One password known to a handful of people could otherwise enrol
     * an unbounded number of long-lived tokens, and the list a person is meant to
     * police would become unreadable at exactly the point it mattered.
     */
    private void pruneOldestBeyondLimit(String mailbox) {
        Map<String, Instant> newestUse = new LinkedHashMap<>();
        for (DeviceToken token : repository.findByMailbox(mailbox)) {
            newestUse.merge(token.getFamilyId(), token.getLastSeenAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        // One slot is left for the device about to be enrolled.
        int keep = Math.max(0, settings.getMaxPerMailbox() - 1);
        if (newestUse.size() <= keep) return;

        List<Map.Entry<String, Instant>> oldestFirst = new ArrayList<>(newestUse.entrySet());
        oldestFirst.sort(Comparator.comparing(Map.Entry::getValue));
        for (int i = 0; i < oldestFirst.size() - keep; i++) {
            repository.deleteByFamilyId(oldestFirst.get(i).getKey());
        }
    }

    /**
     * Constant time, because this compares a stored digest against one computed from
     * a value the caller chose. A short circuiting equals leaks how many leading
     * characters were right, which is enough to walk a token out one byte at a time.
     */
    private static boolean secretMatches(String presented, String storedHash) {
        if (presented == null || storedHash == null) return false;
        return MessageDigest.isEqual(
                ApiKeyHasher.sha256(presented).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static String canonical(String mailbox) {
        return mailbox == null ? "" : mailbox.trim().toLowerCase(Locale.ROOT);
    }
}
