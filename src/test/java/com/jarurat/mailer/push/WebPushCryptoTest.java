package com.jarurat.mailer.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The crypto, checked against the specification's own worked example rather than
 * against a restatement of this implementation's arithmetic.
 *
 * RFC 8291 section 5 publishes a complete encryption: both key pairs, the auth secret,
 * the salt and the exact bytes on the wire. That makes the first test here the only
 * kind of test worth having for a cipher, because it fails if any single step is
 * wrong - the HKDF info strings and their easily lost NUL terminators, the order the
 * two public keys go into the key info, the padding delimiter, the record size in the
 * header, the length of the key id. A round trip alone would pass with all of those
 * consistently wrong, and the browser would then be the thing that discovered it.
 */
class WebPushCryptoTest {

    // RFC 8291 section 5, verbatim.
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String EXPECTED_BODY =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
                    + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
                    + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    @DisplayName("produces the exact body RFC 8291 section 5 publishes")
    void matchesTheSpecificationExample() throws Exception {
        byte[] expected = VapidKeys.decode(EXPECTED_BODY);
        // The salt is the first sixteen bytes of the published body, so taking it from
        // there rather than retyping it removes one way this test could be wrong about
        // what it is checking.
        byte[] salt = Arrays.copyOfRange(expected, 0, 16);

        byte[] actual = WebPushCrypto.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                VapidKeys.decode(UA_PUBLIC),
                VapidKeys.decode(AUTH_SECRET),
                salt,
                senderKeyPair());

        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(actual))
                .isEqualTo(EXPECTED_BODY);
    }

    @Test
    @DisplayName("what the browser would decrypt is what we encrypted")
    void roundTripsWithTheSubscriptionKeys() {
        byte[] body = WebPushCrypto.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                VapidKeys.decode(UA_PUBLIC),
                VapidKeys.decode(AUTH_SECRET));

        byte[] recovered = WebPushCrypto.decrypt(body,
                VapidKeys.decode(UA_PRIVATE), VapidKeys.decode(UA_PUBLIC),
                VapidKeys.decode(AUTH_SECRET));

        assertThat(new String(recovered, StandardCharsets.UTF_8)).isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("a fresh ephemeral key per message, so the same text twice is not the same bytes twice")
    void everyMessageGetsItsOwnEphemeralKey() {
        byte[] uaPublic = VapidKeys.decode(UA_PUBLIC);
        byte[] auth = VapidKeys.decode(AUTH_SECRET);
        byte[] text = PLAINTEXT.getBytes(StandardCharsets.UTF_8);

        // Not a style point. If two identical notifications produced identical bytes,
        // the push service would be able to tell that the same thing was said twice,
        // which is exactly the sort of thing encryption is here to stop it learning.
        assertThat(WebPushCrypto.encrypt(text, uaPublic, auth))
                .isNotEqualTo(WebPushCrypto.encrypt(text, uaPublic, auth));
    }

    @Test
    @DisplayName("refuses a payload that will not fit one record instead of sending a 413")
    void refusesAnOversizedPayload() {
        byte[] tooBig = new byte[WebPushCrypto.MAX_PLAINTEXT + 1];
        assertThatThrownBy(() -> WebPushCrypto.encrypt(tooBig,
                VapidKeys.decode(UA_PUBLIC), VapidKeys.decode(AUTH_SECRET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ceiling");
    }

    @Test
    @DisplayName("the VAPID header is a real ES256 signature over the origin, not the endpoint")
    void signsTheVapidToken() throws Exception {
        KeyPair pair = VapidKeys.generate();
        VapidKeys keys = new VapidKeys(
                VapidKeys.base64Url(VapidKeys.bytesOf((ECPublicKey) pair.getPublic())),
                VapidKeys.base64Url(pair.getPrivate().getEncoded()),
                "mailto:postmaster@jarurat.care");
        assertThat(keys.enabled()).isTrue();

        String header = WebPushCrypto.vapidAuthorization(
                URI.create("https://web.push.apple.com/QK9tokenthatisacapability"),
                keys, Instant.parse("2026-08-29T10:00:00Z"));

        assertThat(header).startsWith("vapid t=").contains(", k=" + keys.applicationServerKey());

        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        String claims = new String(VapidKeys.decode(parts[1]), StandardCharsets.UTF_8);
        // The audience is the origin and not the endpoint. The endpoint path is the
        // bearer capability for that one device and a JWT is not a secret, so putting
        // the whole endpoint in a claim would be handing it out.
        assertThat(claims).contains("\"aud\":\"https://web.push.apple.com\"")
                .doesNotContain("QK9tokenthatisacapability")
                .contains("\"sub\":\"mailto:postmaster@jarurat.care\"")
                // Twelve hours, comfortably inside the twenty four hour ceiling.
                .contains("\"exp\":" + Instant.parse("2026-08-29T22:00:00Z").getEpochSecond());

        // P1363 and not DER: sixty four bytes, two raw thirty two byte integers. This
        // is the detail that usually drags a project into BouncyCastle.
        byte[] signature = VapidKeys.decode(parts[2]);
        assertThat(signature).hasSize(64);

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    @DisplayName("a public key whose coordinate has a leading zero still encodes as sixty five bytes")
    void encodesShortCoordinatesLeftPadded() {
        // BigInteger drops leading zeroes, so roughly one key in two hundred and fifty
        // has a coordinate that is thirty one bytes long. A round of generated keys is
        // a cheap way to keep that from being discovered in production.
        for (int i = 0; i < 200; i++) {
            byte[] point = VapidKeys.bytesOf((ECPublicKey) VapidKeys.generate().getPublic());
            assertThat(point).hasSize(65);
            assertThat(point[0]).isEqualTo((byte) 0x04);
        }
    }

    private static KeyPair senderKeyPair() throws Exception {
        ECParameterSpec params = VapidKeys.curveParameters();
        return new KeyPair(
                VapidKeys.publicKeyFrom(VapidKeys.decode(AS_PUBLIC), params),
                VapidKeys.privateKeyFrom(VapidKeys.decode(AS_PRIVATE), params));
    }
}
