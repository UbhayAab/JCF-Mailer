package com.jarurat.mailer.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The one P-256 key pair this application signs push messages with, loaded from
 * configuration and never generated at runtime.
 *
 * WHY THIS KEY PAIR IS A DATA MIGRATION AND NOT A SECRET THAT CAN BE ROTATED FREELY
 * ---------------------------------------------------------------------------------
 * A browser hands its push service the public half of this pair at the moment it
 * subscribes, and RFC 8292 requires the push service to reject any later message
 * signed by a different key. So the public key is baked into every PushSubscription
 * the fleet already holds. Replace the pair and every one of them becomes
 * undeliverable: the push service answers 403 for the rest of that subscription's
 * life, nothing fails on the browser side, and the only symptom is that notifications
 * stop for everybody at once with no error anywhere. Losing the private key has the
 * same effect and cannot be undone, because a subscription cannot be re-pointed at a
 * new key from this side.
 *
 * Therefore: generate the pair once with VapidKeygen, put it in the environment file
 * beside OTP_PEPPER, and back it up with the database rather than with the source. If
 * it is genuinely lost the recovery is a migration and not a restart: truncate
 * push_subscription, ship a new pair, and every device has to open the mail screen and
 * subscribe again. Say that out loud before doing it, because no device will report it.
 *
 * The pair is deliberately allowed to be absent. Push is an addition to a mail client
 * that already works without it, and an application that refuses to boot over an
 * optional notification key is a worse failure than one that boots and does not
 * notify. Absent means disabled, loudly: the reason is printed at startup and returned
 * from GET /api/mail/push/config, so it lands on a screen somebody is already looking
 * at rather than only in a log nobody reads.
 *
 * A malformed pair is treated exactly like an absent one, for the same reason. It is
 * an operator typo, it happens at deploy time, and taking the mailbox down over it
 * would turn a broken notification into a broken mail client.
 */
@Component
public class VapidKeys {

    /** secp256r1, the only curve RFC 8292 permits. */
    static final String CURVE = "secp256r1";

    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String publicKeyBase64Url;
    private final String subject;

    /** Null when push is usable. A sentence for a person when it is not. */
    private final String disabledReason;

    public VapidKeys(@Value("${jarurat.push.vapid.public-key:}") String configuredPublic,
                     @Value("${jarurat.push.vapid.private-key:}") String configuredPrivate,
                     @Value("${jarurat.push.vapid.subject:}") String configuredSubject) {

        String subj = configuredSubject == null ? "" : configuredSubject.trim();
        String reason = null;
        ECPublicKey pub = null;
        ECPrivateKey priv = null;
        String pubB64 = null;

        if (blank(configuredPublic) || blank(configuredPrivate)) {
            reason = "No VAPID key pair is configured, so this server cannot send push "
                    + "notifications. Set PUSH_VAPID_PUBLIC_KEY and PUSH_VAPID_PRIVATE_KEY in the "
                    + "environment file. Generate them once with VapidKeygen and never regenerate "
                    + "them: a new pair invalidates every subscription the fleet already holds.";
        } else if (!subj.startsWith("mailto:") && !subj.startsWith("https://")) {
            // Apple answers 403 BadJwtToken for a sub claim that is not a real mailto or
            // https URL, and it does so only on Apple, which makes a missing contact look
            // like an iPhone bug rather than one configuration line. Refusing to enable
            // push without one turns that into a sentence on the settings screen instead.
            reason = "PUSH_VAPID_SUBJECT is not a real contact, so push is off. Apple rejects a "
                    + "VAPID token whose sub claim is not a mailto: or https: URL, and it fails on "
                    + "iPhones only, which is the platform this feature exists for. Set it to "
                    + "something like mailto:postmaster@jarurat.care.";
        } else {
            try {
                ECParameterSpec params = curveParameters();
                byte[] pointBytes = decode(configuredPublic);
                pub = publicKeyFrom(pointBytes, params);
                priv = privateKeyFrom(decode(configuredPrivate), params);
                pubB64 = base64Url(pointBytes);
            } catch (Exception e) {
                reason = "The configured VAPID key pair could not be read (" + e.getMessage()
                        + "), so push is off. Both halves are base64url: the public key is the 65 "
                        + "byte uncompressed P-256 point, the private key is either the PKCS#8 body "
                        + "or the raw 32 byte scalar.";
                pub = null;
                priv = null;
                pubB64 = null;
            }
        }

        this.publicKey = pub;
        this.privateKey = priv;
        this.publicKeyBase64Url = pubB64;
        this.subject = subj;
        this.disabledReason = reason;

        if (reason != null) {
            // The same shape as the OTP pepper notice, and for the same reason: the one
            // moment an operator is definitely watching is the deploy.
            System.out.println("PUSH: " + reason);
        }
    }

    public boolean enabled() { return disabledReason == null; }

    /** A sentence to show a person, or null when everything is configured. */
    public String disabledReason() { return disabledReason; }

    /**
     * What the browser passes to pushManager.subscribe as applicationServerKey, and
     * what goes in the k parameter of the Authorization header. The same bytes in both
     * places, which is the property that makes a subscription and a signature belong
     * to each other.
     */
    public String applicationServerKey() { return publicKeyBase64Url; }

    public ECPublicKey publicKey() { return publicKey; }

    public ECPrivateKey privateKey() { return privateKey; }

    /** The sub claim. Who a push service operator complains to about our traffic. */
    public String subject() { return subject; }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /**
     * The named curve's domain parameters, which both halves of the pair need and
     * neither carries. There is no constant for this in the JDK, so it comes through
     * AlgorithmParameters, which is the supported way of naming a curve.
     */
    static ECParameterSpec curveParameters() throws Exception {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(CURVE));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    /**
     * SEC1 uncompressed point, 0x04 followed by X and Y at 32 bytes each. It is the
     * only encoding a browser and a push service both understand, and it is not any of
     * the encodings the JDK will produce on its own, so it is spelled out here and in
     * bytesOf below.
     */
    static ECPublicKey publicKeyFrom(byte[] uncompressedPoint, ECParameterSpec params) throws Exception {
        if (uncompressedPoint.length != 65 || uncompressedPoint[0] != 0x04) {
            throw new IllegalArgumentException("expected a 65 byte uncompressed P-256 point, got "
                    + uncompressedPoint.length + " bytes");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 33, 65));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
    }

    /**
     * Accepts either encoding on purpose. PKCS#8 is what openssl and Stalwart's own
     * jmap.webPushKey setting deal in, so one generated PEM can serve both places; the
     * raw scalar is what most web push tooling prints. Guessing costs one failed
     * KeyFactory call and saves a support conversation.
     */
    static ECPrivateKey privateKeyFrom(byte[] material, ECParameterSpec params) throws Exception {
        if (material.length == 32) {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, material), params));
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(material));
    }

    /** The 65 byte uncompressed point for a public key, left padded as SEC1 requires. */
    public static byte[] bytesOf(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        copyLeftPadded(key.getW().getAffineX(), out, 1);
        copyLeftPadded(key.getW().getAffineY(), out, 33);
        return out;
    }

    /**
     * BigInteger.toByteArray prepends a zero whenever the top bit is set and drops
     * leading zeroes when it is not, so one coordinate comes back as 31, 32 or 33
     * bytes. SEC1 wants exactly 32 every time. Getting this wrong yields a key that
     * works for most key pairs and fails for roughly one in two hundred and fifty,
     * which is the worst failure rate there is to debug.
     */
    private static void copyLeftPadded(BigInteger value, byte[] into, int offset) {
        byte[] raw = value.toByteArray();
        int from = Math.max(0, raw.length - 32);
        int length = raw.length - from;
        System.arraycopy(raw, from, into, offset + (32 - length), length);
    }

    /** Both base64 alphabets, padded or not. Configuration gets copied by hand. */
    static byte[] decode(String value) {
        String trimmed = value.trim().replace("\r", "").replace("\n", "");
        if (trimmed.startsWith("-----")) {
            // A whole PEM block pasted in one piece. Strip the armour.
            trimmed = trimmed.replaceAll("-----[A-Z ]+-----", "");
        }
        String normalised = trimmed.replace('+', '-').replace('/', '_');
        int pad = normalised.indexOf('=');
        if (pad >= 0) normalised = normalised.substring(0, pad);
        return Base64.getUrlDecoder().decode(normalised);
    }

    public static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** One fresh pair. Only VapidKeygen and the tests call this. */
    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(CURVE));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("This JVM cannot generate a P-256 key pair", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
