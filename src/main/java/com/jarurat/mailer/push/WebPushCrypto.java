package com.jarurat.mailer.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Arrays;

/**
 * RFC 8291 message encryption and RFC 8292 request signing, written against the JDK
 * and nothing else.
 *
 * WHY THERE IS NO DEPENDENCY HERE
 * ------------------------------------------------------------------------------
 * The usual answer is nl.martijndwars:web-push, which pulls in BouncyCastle and a
 * second HTTP client: several megabytes of jar and a second JCE provider to keep
 * patched, on a 1.8GB box that runs one jar, to replace what is below against a
 * specification that has not changed since 2017. The single detail that normally
 * drags people to BouncyCastle is that VAPID wants an ECDSA signature as raw R
 * concatenated with S rather than the DER sequence Java emits by default, and JDK 9
 * added SHA256withECDSAinP1363Format, which is exactly that. Everything else is
 * KeyAgreement, Mac and Cipher, all of which have been in the platform for years.
 *
 * WHAT AN OPERATOR OF THE PUSH SERVICE CAN STILL SEE
 * ------------------------------------------------------------------------------
 * Google, Apple and Mozilla relay these messages and cannot read one. The body is
 * AES-128-GCM under a key derived from a shared secret between this server and one
 * specific browser installation, so the subject line, the sender and the preview are
 * opaque to them. What they can see, and what no encryption hides, is the endpoint
 * URL, and therefore which device this is; the exact time each message was sent, and
 * so the rhythm of a person's mail; the size of the payload, which leaks roughly how
 * long a subject line is; and the TTL, Urgency and Topic headers, which are routing
 * metadata and are sent in the clear by design. Urgency in particular is a claim
 * about how important a message is, so it is set from the lane and never from
 * anything a sender chose. Nothing unencrypted is ever sent: a subscription without
 * keys would let us POST plain JSON, and this class has no path that does it.
 */
final class WebPushCrypto {

    /** RFC 8188. The trailing NUL is part of the string and is easy to lose. */
    private static final byte[] CEK_INFO = infoBytes("Content-Encoding: aes128gcm");
    private static final byte[] NONCE_INFO = infoBytes("Content-Encoding: nonce");
    private static final byte[] WEBPUSH_INFO = infoBytes("WebPush: info");

    /**
     * One record, and the largest every push service accepts. The header is 86 bytes
     * (16 salt, 4 record size, 1 key id length, 65 key id) and GCM adds a 16 byte tag
     * over the plaintext plus its one byte padding delimiter, so the plaintext ceiling
     * is 4096 - 86 - 16 - 1. Anything longer has to be shortened before it gets here,
     * because the push service answers 413 and the notification is simply lost.
     */
    static final int RECORD_SIZE = 4096;
    static final int MAX_PLAINTEXT = RECORD_SIZE - 86 - 16 - 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushCrypto() {}

    // ------------------------------------------------------------------
    // RFC 8291 encryption
    // ------------------------------------------------------------------

    /**
     * The aes128gcm body for one push message.
     *
     * uaPublicKey is the subscription's p256dh as raw bytes, authSecret its auth as
     * raw bytes. The ephemeral key pair is generated per message, which is what makes
     * two identical notifications to the same device produce two unrelated
     * ciphertexts, so the push service cannot tell that the same thing was said twice.
     */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublicKey, byte[] authSecret) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublicKey, authSecret, salt, VapidKeys.generate());
    }

    /**
     * The same thing with the two random inputs supplied, which is the only way to
     * check this implementation against the worked example in RFC 8291 section 5. Both
     * are random in production and neither is a secret afterwards: the salt travels in
     * the header and the ephemeral public key travels beside it.
     */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublicKey, byte[] authSecret,
                          byte[] salt, KeyPair serverKey) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("Push payload is " + plaintext.length
                    + " bytes, over the " + MAX_PLAINTEXT + " byte ceiling for one aes128gcm record");
        }
        try {
            byte[] serverPublic = VapidKeys.bytesOf((ECPublicKey) serverKey.getPublic());

            ECPublicKey uaKey = VapidKeys.publicKeyFrom(uaPublicKey, VapidKeys.curveParameters());
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(serverKey.getPrivate());
            agreement.doPhase(uaKey, true);
            byte[] sharedSecret = agreement.generateSecret();

            // RFC 8291 section 3.3. The two public keys go into the info in a fixed
            // order, receiver first, so that a message encrypted for one subscription
            // cannot be replayed against another even with the same shared secret.
            byte[] keyInfo = concat(WEBPUSH_INFO, uaPublicKey, serverPublic);
            byte[] ikm = hkdf(authSecret, sharedSecret, keyInfo, 32);

            byte[] contentKey = hkdf(salt, ikm, CEK_INFO, 16);
            byte[] nonce = hkdf(salt, ikm, NONCE_INFO, 12);

            // RFC 8188 section 2: the final record ends with a delimiter octet of 2.
            // A 1 here means "another record follows" and every push service will
            // decrypt the message and then discard it, silently.
            byte[] padded = Arrays.copyOf(plaintext, plaintext.length + 1);
            padded[plaintext.length] = 2;

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(contentKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(padded);

            ByteArrayOutputStream body = new ByteArrayOutputStream(86 + ciphertext.length);
            body.write(salt);
            body.write(ByteBuffer.allocate(4).putInt(RECORD_SIZE).array());
            body.write(serverPublic.length);
            body.write(serverPublic);
            body.write(ciphertext);
            return body.toByteArray();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt a push payload", e);
        }
    }

    /**
     * The inverse, which exists so the round trip can be tested end to end rather than
     * asserted against a restatement of the same arithmetic. Nothing in production
     * calls it: only the browser ever decrypts one of these.
     */
    static byte[] decrypt(byte[] body, byte[] uaPrivateScalar, byte[] uaPublicKey, byte[] authSecret) {
        try {
            byte[] salt = Arrays.copyOfRange(body, 0, 16);
            int keyIdLength = body[20] & 0xff;
            byte[] serverPublic = Arrays.copyOfRange(body, 21, 21 + keyIdLength);
            byte[] ciphertext = Arrays.copyOfRange(body, 21 + keyIdLength, body.length);

            var params = VapidKeys.curveParameters();
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(VapidKeys.privateKeyFrom(uaPrivateScalar, params));
            agreement.doPhase(VapidKeys.publicKeyFrom(serverPublic, params), true);
            byte[] sharedSecret = agreement.generateSecret();

            byte[] ikm = hkdf(authSecret, sharedSecret,
                    concat(WEBPUSH_INFO, uaPublicKey, serverPublic), 32);
            byte[] contentKey = hkdf(salt, ikm, CEK_INFO, 16);
            byte[] nonce = hkdf(salt, ikm, NONCE_INFO, 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(contentKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] padded = cipher.doFinal(ciphertext);

            int end = padded.length;
            while (end > 0 && padded[end - 1] == 0) end--;   // RFC 8188 zero padding
            return Arrays.copyOf(padded, Math.max(0, end - 1)); // drop the delimiter
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt a push payload", e);
        }
    }

    /**
     * HKDF-SHA256, extract then expand, for output no longer than one hash block.
     * Every derivation web push needs is 32 bytes or fewer, so the counter never goes
     * past one and the loop that would otherwise be here is a single Mac call.
     */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);

            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 1);
            return Arrays.copyOf(mac.doFinal(), length);
        } catch (Exception e) {
            throw new IllegalStateException("HKDF failed", e);
        }
    }

    // ------------------------------------------------------------------
    // RFC 8292 signing
    // ------------------------------------------------------------------

    /**
     * The Authorization header value for one push request.
     *
     * The audience is the endpoint's origin and not the endpoint itself, which matters
     * because the endpoint path is the bearer capability for that device and a JWT is
     * not a secret. The expiry is twelve hours, comfortably inside the twenty-four
     * hour ceiling RFC 8292 sets and long enough that a clock a few minutes out on
     * either side is not a rejection.
     */
    static String vapidAuthorization(URI endpoint, VapidKeys keys, Instant now) {
        String origin = endpoint.getScheme() + "://" + endpoint.getHost()
                + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());

        String header = VapidKeys.base64Url(
                "{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = VapidKeys.base64Url(("{\"aud\":\"" + origin + "\",\"exp\":"
                + now.plusSeconds(12 * 3600).getEpochSecond()
                + ",\"sub\":\"" + keys.subject() + "\"}").getBytes(StandardCharsets.UTF_8));

        String signingInput = header + "." + claims;
        return "vapid t=" + signingInput + "." + sign(signingInput, keys.privateKey())
                + ", k=" + keys.applicationServerKey();
    }

    /**
     * ES256 in the shape JWS wants: the two 32 byte integers laid end to end, not the
     * DER sequence Signature normally emits. P1363 format is that, and it is the whole
     * reason this file needs no third party crypto library.
     */
    private static String sign(String signingInput, ECPrivateKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return VapidKeys.base64Url(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the VAPID token", e);
        }
    }

    // ------------------------------------------------------------------

    private static byte[] infoBytes(String label) {
        byte[] raw = label.getBytes(StandardCharsets.US_ASCII);
        return Arrays.copyOf(raw, raw.length + 1); // the NUL terminator the RFC counts
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
