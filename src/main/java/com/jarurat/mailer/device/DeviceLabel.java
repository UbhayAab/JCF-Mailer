package com.jarurat.mailer.device;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * Turns a user agent into something a person can recognise in a list of their own
 * devices.
 *
 * The list is the only way anybody can tell a token they meant to issue from one
 * they did not, so the label has one job: let somebody looking at three rows say
 * "that iPhone is mine, that Windows one is not". It is deliberately coarse. A
 * version number would age into noise, and the full user agent is both unreadable
 * and a small fingerprint of the person that we would then be storing for months.
 *
 * DeviceHints already reads these headers to pick a landing page and is deliberately
 * not reused: it answers one narrow question, is this a phone, and widening it to
 * produce display strings would put a routing decision and a cosmetic one in the same
 * pattern. This class is allowed to be wrong about a browser; that one is not.
 */
final class DeviceLabel {

    private static final int MAX = 120;

    private DeviceLabel() {
    }

    static String of(HttpServletRequest request) {
        if (request == null) return "Unknown device";
        String agent = request.getHeader("User-Agent");
        if (agent == null || agent.isBlank()) return "Unknown device";

        String lower = agent.toLowerCase(Locale.ROOT);
        String platform = platform(lower);
        String browser = browser(lower);
        String label = browser.isEmpty() ? platform : platform + " " + browser;
        return label.length() > MAX ? label.substring(0, MAX) : label;
    }

    private static String platform(String agent) {
        if (agent.contains("iphone")) return "iPhone";
        if (agent.contains("ipad")) return "iPad";
        if (agent.contains("android")) return agent.contains("mobile") ? "Android phone" : "Android";
        if (agent.contains("windows")) return "Windows";
        if (agent.contains("mac os x") || agent.contains("macintosh")) return "Mac";
        if (agent.contains("cros")) return "Chromebook";
        if (agent.contains("linux")) return "Linux";
        return "Unknown device";
    }

    /**
     * Order matters here and it is the one thing worth getting right. Every Chromium
     * browser still says "chrome" and "safari" in its user agent, and Safari says
     * "safari" alone, so the specific names have to be tested before the generic ones
     * or every browser in the building would be labelled Chrome.
     */
    private static String browser(String agent) {
        if (agent.contains("edg/") || agent.contains("edgios") || agent.contains("edga")) return "Edge";
        if (agent.contains("opr/") || agent.contains("opera")) return "Opera";
        if (agent.contains("brave")) return "Brave";
        if (agent.contains("crios")) return "Chrome";
        if (agent.contains("fxios") || agent.contains("firefox")) return "Firefox";
        if (agent.contains("samsungbrowser")) return "Samsung Internet";
        if (agent.contains("chrome") || agent.contains("chromium")) return "Chrome";
        if (agent.contains("safari")) return "Safari";
        return "";
    }
}
