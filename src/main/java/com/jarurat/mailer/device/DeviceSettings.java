package com.jarurat.mailer.device;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The five numbers that decide how long a phone stays signed in and how forgiving
 * rotation is, in one place so a deployment can turn the whole thing off without a
 * rebuild.
 *
 * The kill switch is the reason this is a bean rather than five scattered @Value
 * fields. Persistent sign in is the first thing anybody will want disabled if it
 * ever misbehaves on the live box, and "set DEVICE_REMEMBER_ENABLED=false and
 * restart" has to be a complete answer: with it off, no token is issued, no cookie is
 * read, and every session is the eight hour session it was before.
 */
@Component
public class DeviceSettings {

    private final boolean enabled;
    private final boolean cookieSecure;
    private final Duration validity;
    private final Duration rotationGrace;
    private final int maxPerMailbox;

    public DeviceSettings(
            @Value("${jarurat.device.enabled:true}") boolean enabled,
            @Value("${jarurat.device.cookie-secure:true}") boolean cookieSecure,
            @Value("${jarurat.device.validity-days:180}") int validityDays,
            @Value("${jarurat.device.rotation-grace-seconds:60}") int rotationGraceSeconds,
            @Value("${jarurat.device.max-per-mailbox:12}") int maxPerMailbox) {
        this.enabled = enabled;
        this.cookieSecure = cookieSecure;
        this.validity = Duration.ofDays(Math.max(1, validityDays));
        this.rotationGrace = Duration.ofSeconds(Math.max(0, rotationGraceSeconds));
        this.maxPerMailbox = Math.max(1, maxPerMailbox);
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Off only for a plain HTTP local run. On the live box nginx terminates TLS and
     * this must stay true, because a device cookie sent in the clear once is a
     * mailbox handed over for as long as the token lives.
     */
    public boolean isCookieSecure() { return cookieSecure; }

    /** Slid forward on every use, so a phone in daily use never reaches it. */
    public Duration getValidity() { return validity; }

    /**
     * How long the token a rotation has just spent still counts as an honest late
     * arrival rather than a replay. DeviceTokenService explains why this window has
     * to exist and what it costs.
     */
    public Duration getRotationGrace() { return rotationGrace; }

    /** A bound on the table, and on how many phones one leaked password can enrol. */
    public int getMaxPerMailbox() { return maxPerMailbox; }
}
