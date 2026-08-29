package com.jarurat.mailer.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one place that answers "which machine sent this request".
 *
 * Three separate call sites used to answer it for themselves, and all three were
 * wrong in the same way: they read {@code X-Forwarded-For}, and two of them took
 * its FIRST element. That element is whatever the caller typed. nginx builds the
 * header with {@code $proxy_add_x_forwarded_for}, which APPENDS the real peer to
 * anything the client already sent, so the first element is attacker supplied and
 * only the last is trustworthy.
 *
 * Reading the last element is still not enough here, and this is the part that is
 * easy to miss. {@code application.properties} sets
 * {@code server.forward-headers-strategy=framework}, so Spring Boot registers
 * {@code ForwardedHeaderFilter} at {@code Integer.MIN_VALUE}, ahead of the security
 * chain. That filter REMOVES every {@code X-Forwarded-*} header from the request it
 * passes on, and rewrites {@code getRemoteAddr()} from the first element. By the time
 * any of this application's code runs there is no forwarded header left to read, and
 * the remote address has already become the caller's own claim. A rate limiter keyed
 * on it can be rotated for unlimited attempts and pointed at a chosen office to lock
 * real people out. Both were measured against the running application, not inferred.
 *
 * {@code X-Real-IP} is the answer, for two reasons that both have to hold:
 * nginx sets it with {@code proxy_set_header X-Real-IP $remote_addr}, which
 * overwrites unconditionally rather than appending, so a client cannot inject a value
 * that survives; and it is not one of the seven names {@code ForwardedHeaderFilter}
 * strips, so it actually reaches us. That line is present in
 * {@code /etc/nginx/sites-enabled/jcfmailer} and is load bearing for every security
 * control in this package. Removing it does not break the site, it silently returns
 * the rate limiter to trusting the caller, which is why it is recorded in
 * {@code docs/DEPLOYMENT.md} as well as here.
 *
 * The fallback is the socket address, which is correct when nothing is in front of
 * the application at all, as in a local run or a test. Behind nginx the socket
 * address is loopback for everyone, so the fallback is useless there, but it is never
 * reached there either.
 */
public final class ClientIp {

    /** The header nginx overwrites with the real peer on every proxied request. */
    static final String TRUSTED_HEADER = "X-Real-IP";

    private ClientIp() {
    }

    /**
     * Never null, so a caller can key a map on it without a null check. An empty
     * string means the request carried no usable address, which is treated as one
     * shared bucket rather than as an exemption: an unattributable request must not
     * be cheaper than an attributable one.
     */
    public static String of(HttpServletRequest request) {
        if (request == null) return "";

        String real = request.getHeader(TRUSTED_HEADER);
        if (real != null && !real.isBlank()) {
            // The directive sets a single address, but a comma separated value is
            // cheap to survive and expensive to be surprised by, and the first
            // element is the one nginx wrote.
            int comma = real.indexOf(',');
            String peer = (comma < 0 ? real : real.substring(0, comma)).trim();
            if (!peer.isEmpty()) return peer;
        }

        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote.trim();
    }
}
