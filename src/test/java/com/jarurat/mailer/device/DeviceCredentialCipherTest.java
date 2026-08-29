package com.jarurat.mailer.device;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim the whole design rests on: the ciphertext in the database is worth
 * nothing to anybody who does not also have the cookie.
 *
 * Everything else in this package is plumbing around that one property. If these
 * pass and the rest is broken, a phone asks for a password more often than it should.
 * If these fail, a stolen backup is a set of working mailbox passwords, which is a
 * far worse position than the one this feature started from, where the credential was
 * never written down at all.
 */
class DeviceCredentialCipherTest {

    private static final String MAILBOX = "priya@jarurat.care";
    private static final String PASSWORD = "the mailbox password, correct horse";

    @Test
    @DisplayName("the cookie secret opens the envelope, and reads back exactly what was sealed")
    void aRoundTripReturnsThePassword() {
        String secret = DeviceCookie.mintSecret();
        String envelope = DeviceCredentialCipher.seal(secret, MAILBOX, PASSWORD);

        assertThat(DeviceCredentialCipher.open(secret, MAILBOX, envelope)).contains(PASSWORD);
    }

    @Test
    @DisplayName("the stored row on its own decrypts nothing")
    void aDatabaseRowAloneIsUseless() {
        String secret = DeviceCookie.mintSecret();
        String envelope = DeviceCredentialCipher.seal(secret, MAILBOX, PASSWORD);

        // Everything a database thief holds: the ciphertext, the address it belongs to,
        // and the digest of the secret that the row stores in place of the secret.
        String storedHash = com.jarurat.mailer.security.ApiKeyHasher.sha256(secret);

        assertThat(new String(Base64.getUrlDecoder().decode(envelope)))
                .doesNotContain(PASSWORD);
        assertThat(DeviceCredentialCipher.open(storedHash, MAILBOX, envelope)).isEmpty();
        assertThat(DeviceCredentialCipher.open(DeviceCookie.mintSecret(), MAILBOX, envelope)).isEmpty();
        assertThat(DeviceCredentialCipher.open("", MAILBOX, envelope)).isEmpty();
        assertThat(DeviceCredentialCipher.open(null, MAILBOX, envelope)).isEmpty();
    }

    @Test
    @DisplayName("an envelope moved to another mailbox's row does not open")
    void theMailboxIsBoundIntoTheKey() {
        String secret = DeviceCookie.mintSecret();
        String envelope = DeviceCredentialCipher.seal(secret, MAILBOX, PASSWORD);

        // The address is both the HKDF info and the GCM additional data, so this is a
        // cryptographic refusal and not a comparison somebody could forget to write.
        assertThat(DeviceCredentialCipher.open(secret, "hr@jarurat.care", envelope)).isEmpty();
        // Case and padding are not a different mailbox, though, or a row written by one
        // spelling would be unreadable by the other.
        assertThat(DeviceCredentialCipher.open(secret, " Priya@Jarurat.Care ", envelope)).contains(PASSWORD);
    }

    @Test
    @DisplayName("a single altered byte in the column is refused rather than half decrypted")
    void tamperingIsDetected() {
        String secret = DeviceCookie.mintSecret();
        byte[] raw = Base64.getUrlDecoder().decode(DeviceCredentialCipher.seal(secret, MAILBOX, PASSWORD));

        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        assertThat(DeviceCredentialCipher.open(secret, MAILBOX, tampered)).isEmpty();
    }

    @Test
    @DisplayName("rotation re-seals under fresh key material, so two rows of one device share none")
    void everySealIsUnique() {
        String first = DeviceCredentialCipher.seal(DeviceCookie.mintSecret(), MAILBOX, PASSWORD);
        String second = DeviceCredentialCipher.seal(DeviceCookie.mintSecret(), MAILBOX, PASSWORD);
        String sameSecretTwice = DeviceCookie.mintSecret();

        assertThat(first).isNotEqualTo(second);
        // Even the same secret and the same password twice, because the salt and the
        // nonce are drawn again every time. A repeated nonce under one key is the one
        // mistake AES-GCM does not survive.
        assertThat(DeviceCredentialCipher.seal(sameSecretTwice, MAILBOX, PASSWORD))
                .isNotEqualTo(DeviceCredentialCipher.seal(sameSecretTwice, MAILBOX, PASSWORD));
    }

    @Test
    @DisplayName("junk in the column is an empty answer and never an exception")
    void malformedInputFailsClosed() {
        String secret = DeviceCookie.mintSecret();

        assertThat(DeviceCredentialCipher.open(secret, MAILBOX, "not base64 at all !!"))
                .isEqualTo(Optional.empty());
        assertThat(DeviceCredentialCipher.open(secret, MAILBOX, "")).isEmpty();
        assertThat(DeviceCredentialCipher.open(secret, MAILBOX, null)).isEmpty();
        // A version byte we do not issue: an envelope from a future format has to fail
        // closed rather than be read with today's rules.
        assertThat(DeviceCredentialCipher.open(secret, MAILBOX,
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]))).isEmpty();
    }
}
