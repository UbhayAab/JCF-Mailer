package com.jarurat.mailer.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Executors;

/**
 * One encrypted, signed POST to one push service, and an honest reading of the answer.
 *
 * This class deliberately knows nothing about mailboxes, subscriptions or pruning. It
 * takes a row and some bytes, says what happened, and changes nothing; PushService
 * owns every decision that follows from the answer. Keeping the two apart is what
 * makes the pruning rules testable without a network.
 */
@Component
class WebPushSender {

    private final VapidKeys keys;
    private final HttpClient http;
    private final Duration requestTimeout;

    WebPushSender(VapidKeys keys,
                  @Value("${jarurat.push.connect-timeout-seconds:5}") int connectSeconds,
                  @Value("${jarurat.push.request-timeout-seconds:10}") int requestSeconds) {
        this.keys = keys;
        this.requestTimeout = Duration.ofSeconds(Math.max(1, requestSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectSeconds)))
                // These are real internet hosts with real certificates, so ordinary PKIX
                // validation applies and none of JmapClient's loopback trust relaxation
                // has any business here.
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * Encrypts, signs and sends. The plaintext never leaves this method unencrypted
     * and there is no branch that would let it: a subscription without keys is not
     * something this application will store, so there is no unencrypted path to fall
     * back to.
     */
    PushDelivery send(PushSubscriptionRecord subscription, byte[] plaintext, String urgency, int ttlSeconds) {
        if (!keys.enabled()) {
            return PushDelivery.of(PushDelivery.Outcome.DISABLED, 0, keys.disabledReason());
        }
        if (plaintext.length > WebPushCrypto.MAX_PLAINTEXT) {
            // Caught here rather than at the push service, because a 413 costs a round
            // trip and tells the person nothing they can act on.
            return PushDelivery.of(PushDelivery.Outcome.TOO_LARGE, 0,
                    "Payload is " + plaintext.length + " bytes, over the "
                            + WebPushCrypto.MAX_PLAINTEXT + " byte ceiling for one record");
        }

        URI endpoint;
        try {
            endpoint = URI.create(subscription.getEndpoint());
        } catch (IllegalArgumentException e) {
            return PushDelivery.of(PushDelivery.Outcome.GONE, 0, "Stored endpoint is not a URL");
        }

        byte[] body;
        String authorization;
        try {
            body = WebPushCrypto.encrypt(plaintext,
                    VapidKeys.decode(subscription.getUaPublic()),
                    VapidKeys.decode(subscription.getAuthSecret()));
            authorization = WebPushCrypto.vapidAuthorization(endpoint, keys, Instant.now());
        } catch (RuntimeException e) {
            // A subscription whose stored keys will not parse can never be encrypted
            // for, so it is dead in the only sense that matters here.
            return PushDelivery.of(PushDelivery.Outcome.GONE, 0,
                    "Subscription keys are unusable: " + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", authorization)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                // How long the push service holds it for a device that is offline. A
                // day is right for mail: longer and a person is woken by something they
                // have already read on a laptop, shorter and a phone left charging
                // overnight misses it entirely.
                .header("TTL", String.valueOf(Math.max(0, ttlSeconds)))
                // Routing metadata, sent in the clear. It is set from the lane this
                // application decided and never from anything a sender put in a header,
                // because it is a claim about importance and a stranger does not get to
                // make that claim about somebody else's phone.
                .header("Urgency", urgency)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return classify(response.statusCode(), response);
        } catch (IOException e) {
            return PushDelivery.of(PushDelivery.Outcome.UNREACHABLE, 0,
                    "Could not reach the push service: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushDelivery.of(PushDelivery.Outcome.UNREACHABLE, 0, "Interrupted by shutdown");
        }
    }

    /**
     * The five answers, in the order they have to be tested.
     *
     * 201 is what the specification says success looks like; 200 and 202 come back
     * from real services often enough that treating them as failures would prune
     * healthy subscriptions, so the whole 2xx range counts.
     */
    private PushDelivery classify(int status, HttpResponse<String> response) {
        if (status / 100 == 2) {
            return PushDelivery.of(PushDelivery.Outcome.DELIVERED, status, null);
        }
        if (status == 404 || status == 410) {
            return PushDelivery.of(PushDelivery.Outcome.GONE, status,
                    "The push service says this subscription no longer exists");
        }
        if (status == 429) {
            return new PushDelivery(PushDelivery.Outcome.RATE_LIMITED, status,
                    "Rate limited by the push service", retryAfter(response));
        }
        if (status == 413) {
            return PushDelivery.of(PushDelivery.Outcome.TOO_LARGE, status,
                    "The push service refused the payload as too large");
        }
        return PushDelivery.of(PushDelivery.Outcome.REJECTED, status, snippet(response.body()));
    }

    /**
     * Retry-After comes in two shapes and both are in use: a count of seconds, and an
     * HTTP date. Reading only the first leaves the second parsed as zero, which is a
     * rate limit honoured by ignoring it.
     */
    static Instant retryAfter(HttpResponse<?> response) {
        String header = response.headers().firstValue("retry-after").orElse(null);
        return parseRetryAfter(header, Instant.now());
    }

    static Instant parseRetryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) {
            // No guidance, so pick something long enough to be a real pause and short
            // enough that one throttled minute does not cost a whole morning.
            return now.plusSeconds(60);
        }
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            return now.plusSeconds(Math.max(1, Math.min(seconds, 86_400)));
        } catch (NumberFormatException ignored) {
            // Not a number, so it should be an HTTP date.
        }
        try {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
        } catch (DateTimeParseException e) {
            return now.plusSeconds(60);
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) return null;
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
