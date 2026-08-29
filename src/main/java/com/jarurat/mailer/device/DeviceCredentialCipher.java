package com.jarurat.mailer.device;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Envelope encryption for one mailbox password, keyed by a secret this server does
 * not keep.
 *
 * WHY THIS EXISTS AT ALL. Restoring a console session from a cookie is easy and it
 * is also not the feature. The feature is that mail appears, and mail needs the
 * mailbox password on every JMAP call, which until now lived in
 * InMemoryMailCredentialStore for the length of one eight hour session and nowhere
 * else. To survive a restart that credential has to be at rest somewhere. The
 * obvious way to do that, a column encrypted with a key from the environment file,
 * is the thing this class refuses to be: a server holding both halves can decrypt
 * every mailbox in the building at any moment, without a request, without a person,
 * and without anything in a log to say it happened. So the key is not ours. It is
 * derived from the device secret that lives only in the browser's cookie, and the
 * only moment this process can read a mailbox password is a moment when the person
 * holding that phone sent us a request.
 *
 * THE THREAT MODEL, stated plainly, because the whole design is a set of answers to
 * these three questions.
 *
 * What a database thief gets: nothing usable. The device_token row carries a SHA-256
 * of the token secret, which is 256 random bits and therefore not guessable from its
 * digest, and an envelope whose key is HKDF of that same secret. Neither the key nor
 * the token can be recovered from the row. A full dump of Postgres, a backup tape or
 * a replica yields a table of labels, timestamps and addresses, and no mailbox
 * password and no working cookie. That is the property that makes storing the
 * credential acceptable at all, and it is the one to check first if anybody ever
 * proposes adding a server-held key "so we can re-encrypt in bulk".
 *
 * What a stolen phone gets: the mailbox, exactly as if the thief had picked up an
 * unlocked phone with the mail app already open, which is what they have. The cookie
 * is the credential, so possession of it is possession of the mailbox until it is
 * revoked. It buys nothing beyond that: the restore path grants Role.MAILBOX and
 * never a console authority, so the campaign surface, the subscriber base and the
 * team screen are not reachable with a stolen cookie no matter whose phone it was.
 * The cookie is HttpOnly, so a script injected into a page cannot read it, and
 * SameSite=Lax, so a hostile site cannot make an authenticated request with it.
 *
 * What revocation does: it deletes the row, and deleting the row destroys the only
 * copy of the ciphertext. There is no second key that could open it later and no
 * cache to expire, so a revoked device stops working at the next request and the
 * mailbox password it was carrying ceases to exist anywhere in this system. Rotation
 * has the same property in the other direction: every use writes a new envelope
 * under a new key and drops the old one.
 *
 * WHAT THIS DOES NOT DEFEND AGAINST, so nobody reads more into it than is here. An
 * attacker who is running code inside this JVM, or who can read its heap, sees the
 * plaintext during the request that legitimately decrypts it, the same way they
 * already see it today while a session is open. That is not a regression and it is
 * not fixable at this layer; the fix is OAUTHBEARER, described on MailCredentialStore,
 * which removes the mailbox password from this application entirely and makes this
 * class unnecessary. Until that lands, this is the arrangement that keeps the copy at
 * rest from being worth anything on its own.
 *
 * THE CONSTRUCTION. HKDF-SHA256 (RFC 5869) from the device secret with a per-record
 * random salt, then AES-256-GCM with a per-record random nonce. HKDF is written out
 * over javax.crypto.Mac because the JDK's own KDF API arrived in JDK 24 and this runs
 * on 21, and it is HKDF rather than PBKDF2 because the input is already 256 bits of
 * SecureRandom output: iterating a password stretcher over a full entropy secret buys
 * nothing and costs a delay on every mail request. The mailbox address is bound in
 * twice, as HKDF info and as the GCM additional data, so an envelope moved to another
 * mailbox's row fails to open rather than quietly decrypting somebody else's
 * password. Nothing here is configurable: a cipher with options is a cipher with a
 * weak setting somebody will eventually pick.
 */
public final class DeviceCredentialCipher {

    /** Bumped only if the format below changes; an old envelope then fails closed. */
    private static final byte VERSION = 1;

    private static final String HKDF_INFO = "jcf-device-credential-v1:";
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceCredentialCipher() {
    }

    /**
     * Seals a mailbox password for one device, returning the only thing that is ever
     * written to the database.
     *
     * The salt and nonce are fresh on every call, including the calls that re-seal
     * the same password on rotation, so two rows of one family share no key material
     * and a nonce is never reused under a key.
     */
    public static String seal(String deviceSecret, String mailbox, String credential) {
        if (deviceSecret == null || deviceSecret.isEmpty()) {
            throw new IllegalArgumentException("A device secret is required to seal a credential.");
        }
        if (credential == null || credential.isEmpty()) {
            throw new IllegalArgumentException("There is no credential to seal.");
        }
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(derive(deviceSecret, salt, mailbox), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(mailbox));
            byte[] sealed = cipher.doFinal(credential.getBytes(StandardCharsets.UTF_8));

            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    1 + SALT_BYTES + NONCE_BYTES + sealed.length);
            out.write(VERSION);
            out.write(salt, 0, salt.length);
            out.write(nonce, 0, nonce.length);
            out.write(sealed, 0, sealed.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
        } catch (GeneralSecurityException e) {
            // AES-GCM and HmacSHA256 are both mandatory in every JDK this can run on,
            // so reaching here means a broken installation rather than a bad input, and
            // it must not be reported as an ordinary authentication failure.
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /**
     * Opens an envelope, or answers empty for every reason it might not open.
     *
     * Empty rather than an exception is deliberate and the restore path depends on
     * it. A wrong secret, a tampered envelope, an envelope from a different mailbox
     * and a truncated column are all the same answer here: this cookie does not open
     * this row, degrade to the login page. Telling those apart in the response would
     * only tell an attacker which half of their guess was right.
     */
    public static Optional<String> open(String deviceSecret, String mailbox, String envelope) {
        if (deviceSecret == null || deviceSecret.isEmpty() || envelope == null || envelope.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(envelope);
            if (raw.length <= 1 + SALT_BYTES + NONCE_BYTES || raw[0] != VERSION) return Optional.empty();

            byte[] salt = Arrays.copyOfRange(raw, 1, 1 + SALT_BYTES);
            byte[] nonce = Arrays.copyOfRange(raw, 1 + SALT_BYTES, 1 + SALT_BYTES + NONCE_BYTES);
            byte[] sealed = Arrays.copyOfRange(raw, 1 + SALT_BYTES + NONCE_BYTES, raw.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(derive(deviceSecret, salt, mailbox), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(mailbox));
            return Optional.of(new String(cipher.doFinal(sealed), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** RFC 5869 extract then expand, one 32 byte block, so no counter loop is needed. */
    private static byte[] derive(String deviceSecret, byte[] salt, String mailbox) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(deviceSecret.getBytes(StandardCharsets.UTF_8));

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update((HKDF_INFO + canonical(mailbox)).getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 1);
        byte[] okm = mac.doFinal();

        Arrays.fill(prk, (byte) 0);
        return Arrays.copyOf(okm, KEY_BYTES);
    }

    private static byte[] aad(String mailbox) {
        return canonical(mailbox).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Addresses are case insensitive, so the same mailbox spelled two ways has to
     * derive the same key. Locale.ROOT for the reason LoginAddress gives: a server
     * running under a Turkish locale would otherwise fold "I" to a dotless i and
     * derive a key nobody can reproduce.
     */
    private static String canonical(String mailbox) {
        return mailbox == null ? "" : mailbox.trim().toLowerCase(Locale.ROOT);
    }
}
