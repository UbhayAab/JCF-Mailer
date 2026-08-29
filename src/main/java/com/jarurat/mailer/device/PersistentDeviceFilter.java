package com.jarurat.mailer.device;

import com.jarurat.mailer.mail.MailCredentialStore;
import com.jarurat.mailer.security.ClientIp;
import com.jarurat.mailer.security.DeviceHints;
import com.jarurat.mailer.security.LoginLandingHandler;
import com.jarurat.mailer.security.MailboxUserDetails;
import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * The filter that makes a phone stay signed in, and the only thing in this package
 * that touches a live request.
 *
 * It does two jobs on opposite sides of the same boundary. When a request arrives
 * with no authentication and a device cookie, it restores the session: rotate the
 * token, decrypt the mailbox password with the key the cookie carries, open the
 * mailbox and put a Role.MAILBOX authentication into the context. When a request
 * arrives already signed in with a mailbox open and this browser has no token yet, it
 * enrols the device so the next visit needs no password.
 *
 * WHY ENROLMENT LIVES HERE rather than in LoginLandingHandler, which is where a
 * reader would look for it. The handler offers the console password to the mail
 * server on a virtual thread that outlives the redirect, so at the moment it runs the
 * mailbox is usually not open yet and there is nothing to seal. A filter can simply
 * ask again on the next request, and the next request is the one that renders the
 * mailbox. That the handler is also not this agent's file to change is true and is
 * not the reason; a success handler that had to wait for that thread would have to
 * block the redirect on a network call to Stalwart.
 *
 * WHICH DEVICES ARE ENROLLED, and this is a deliberate restriction rather than an
 * oversight. Only a session that is mail-only, or a phone that has not asked for the
 * console, gets a token. A restored session holds Role.MAILBOX and nothing else, so
 * enrolling a laptop that somebody runs Campaign Studio on would mean that the next
 * morning they are silently signed in as a mail-only user, bounced from /app to /mail
 * by PageController, and left with no way back to the console except signing out
 * first. On a phone the mailbox IS the product and that is the right landing anyway.
 * The laptop keeps the eight hour session it always had.
 *
 * EVERY FAILURE ENDS AT THE LOGIN PAGE. A missing row, a wrong secret, an expired
 * token, a replayed one, a credential the cookie will not open, a mail server that
 * refuses the password, or a database that is simply down: all of them leave the
 * context untouched and the request continues unauthenticated, which is exactly what
 * happens today when a session times out. There is no path here that authenticates
 * somebody without also opening their mailbox, because a Role.MAILBOX session with no
 * mailbox behind it renders a screen asking for a password that the person has no
 * reason to expect, which is the half-authenticated state this must never produce.
 *
 * Deliberately not a bean, for the same reason MailboxAuthenticationProvider is not
 * one. Spring Boot registers every Filter bean in the servlet container as well, so an
 * annotation here would put this on the front of every request in the application
 * including the stateless API chain, running twice on the console chain and once
 * outside any security chain at all. SecurityConfig constructs it instead, which is
 * also the file that says where in the chain it belongs.
 */
public class PersistentDeviceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PersistentDeviceFilter.class);

    /**
     * Set once this session has either enrolled a device or decided not to, so the
     * question costs one lookup per session rather than one per request.
     */
    static final String SESSION_CHECKED = "jarurat.device.checked";

    /**
     * Set once the person has asked to stay signed in, and read on every later request
     * in the session.
     *
     * It is a session attribute rather than a parameter read at the moment of enrolment
     * because the two do not happen together. The box is ticked on the sign-in form, but
     * a mailbox credential often does not exist yet at that point: the login success
     * handler opens the mailbox on a virtual thread that outlives the redirect, and
     * somebody whose console and mailbox passwords differ supplies the second one later
     * still, through the unlock sheet. Both of those requests carry the parameter, and
     * enrolment happens on whichever request first finds a credential to seal.
     */
    static final String REMEMBER_REQUESTED = "jarurat.device.remember";

    /** The form field on both the sign-in form and the unlock sheet. */
    private static final String REMEMBER_PARAM = "remember";

    /**
     * Records consent the moment it arrives, on whichever request carries it.
     *
     * Only ever writes true. A later request without the parameter is not a withdrawal:
     * signing out is, and so is revoking the device from the devices screen. Treating a
     * missing parameter as "no" would un-enrol somebody on their next ordinary page load.
     */
    private static void noteConsent(HttpServletRequest request, HttpSession session) {
        if (session == null) return;
        String asked = request.getParameter(REMEMBER_PARAM);
        if (asked == null) return;
        if (asked.equalsIgnoreCase("true") || asked.equals("1")
                || asked.equalsIgnoreCase("on") || asked.equalsIgnoreCase("yes")) {
            session.setAttribute(REMEMBER_REQUESTED, Boolean.TRUE);
        }
    }

    /**
     * Paths this filter has no business on. The login POST is the important one: the
     * authentication filter is about to replace whatever context exists, so restoring
     * one first would rotate a token for nothing. The rest are the static surface,
     * which every browser requests with the cookie attached and which must never cost
     * a database lookup.
     */
    private static final String[] IGNORED_PREFIXES = {
            "/css/", "/js/", "/icons/", "/logo", "/favicon", "/apple-touch-icon",
            "/manifest.webmanifest", "/sw.js", "/offline.html", "/api/mailer/", "/api/sns/"
    };

    private final DeviceSettings settings;
    private final DeviceTokenService tokens;
    private final MailboxAccess mailboxes;
    private final MailCredentialStore credentials;

    /**
     * The same pair Spring Security installs by default on a chain that does not
     * configure one, so a context saved here is loaded back by SecurityContextHolderFilter
     * on the next request exactly as one saved by the login filter would be.
     */
    private final SecurityContextRepository contexts = new DelegatingSecurityContextRepository(
            new RequestAttributeSecurityContextRepository(),
            new HttpSessionSecurityContextRepository());

    public PersistentDeviceFilter(DeviceSettings settings, DeviceTokenService tokens,
                                  MailboxAccess mailboxes, MailCredentialStore credentials) {
        this.settings = settings;
        this.tokens = tokens;
        this.mailboxes = mailboxes;
        this.credentials = credentials;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!settings.isEnabled()) return true;

        String path = path(request);
        if ("POST".equals(request.getMethod()) && "/login".equals(path)) return true;
        for (String prefix : IGNORED_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * The request path below the context, taken from the URI rather than from
     * getServletPath(). The two agree under the dispatcher's "/" mapping, but
     * getServletPath() is empty under several other mappings and under MockMvc, and a
     * path test that silently reads "" would quietly stop skipping anything.
     */
    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return "";
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length()) : uri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (isSignedIn(auth)) {
                rememberThisDevice(request, response, auth);
            } else {
                restore(request, response);
            }
        } catch (RuntimeException e) {
            // Nothing in this filter is worth failing a request over, and the cookie is
            // deliberately left alone: a database that is briefly unreachable must not
            // sign the whole fleet out permanently on its way back up.
            log.warn("Persistent device handling failed; continuing without it.", e);
        }
        chain.doFilter(request, response);
    }

    /**
     * Restores the console session and the mailbox pin together, or neither.
     *
     * The session id is changed when one already exists. A visitor arriving with a
     * session cookie somebody else planted and a stolen device cookie would otherwise
     * end up authenticated inside a session that the planter also holds the id for,
     * which is the fixation attack the login path already defends against with
     * sessionFixation().newSession(). Changing the id keeps the attributes, which
     * matters because the desktop preference DeviceHints stores lives in them.
     */
    private void restore(HttpServletRequest request, HttpServletResponse response) {
        Optional<DeviceCookie.Presented> presented = DeviceCookie.parse(request);
        if (presented.isEmpty()) return;

        Optional<DeviceTokenService.Restored> restored =
                tokens.restore(presented.get(), ClientIp.of(request), response);
        if (restored.isEmpty()) return;

        String mailbox = restored.get().mailbox();
        HttpSession session = request.getSession(false);
        if (session != null) {
            request.changeSessionId();
        } else {
            session = request.getSession(true);
        }

        try {
            // The password is offered to Stalwart rather than trusted, exactly as it is
            // at an interactive unlock. A password changed on the mail server since the
            // token was sealed is refused here, which is the point: the device must not
            // outlive the credential it carries.
            mailboxes.open(session, mailbox, restored.get().mailboxSecret());
        } catch (RuntimeException e) {
            // The token was good and the mailbox will not open, so the device is now
            // useless and keeping it would mean this failure repeats on every request
            // for months. Revoke it and let the person sign in with the new password.
            log.info("Device token for {} no longer opens the mailbox; revoking it.", mailbox);
            tokens.forget(request, response);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        MailboxUserDetails principal = new MailboxUserDetails(mailbox);
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        // This session now holds the device it was restored from, so the enrolment side
        // of this filter has nothing to do for the rest of it.
        session.setAttribute(SESSION_CHECKED, Boolean.TRUE);
    }

    /**
     * Enrols this browser the first time a signed-in session is seen with a mailbox
     * actually open.
     *
     * The credential comes out of MailCredentialStore rather than from anything on the
     * request, because by this point the password has been verified by Stalwart and
     * this is the only place it still exists. If the mailbox is not open yet, which is
     * the normal state for the first request or two after a login, this simply does
     * nothing and asks again on the next request.
     */
    private void rememberThisDevice(HttpServletRequest request, HttpServletResponse response,
                                    Authentication auth) {
        HttpSession session = request.getSession(false);
        if (session == null) return;
        // Before the short-circuit below, so consent that arrives on a later request
        // than the one which first looked is still recorded.
        noteConsent(request, session);
        if (Boolean.TRUE.equals(session.getAttribute(SESSION_CHECKED))) return;

        if (!LoginLandingHandler.isMailOnly(auth.getAuthorities()) && !DeviceHints.wantsMailbox(request)) {
            session.setAttribute(SESSION_CHECKED, Boolean.TRUE);
            return;
        }

        // The sign-in form carries a "Keep me signed in on this device" box and this is
        // what honours it. Enrolling without asking would have made that control
        // decorative: somebody who deliberately cleared it on a shared machine would
        // still have been handed a credential valid for six months, and the copy beside
        // the box promises the opposite. A long-lived credential has to be something a
        // person chose, so the absence of consent is treated as a refusal rather than as
        // a missing value.
        // Deliberately does NOT set SESSION_CHECKED. Consent and a sealable credential
        // arrive on different requests: somebody whose console and mailbox passwords
        // differ ticks the box on the sign-in form and only supplies the mailbox
        // password later, through the unlock sheet. Marking the session as settled here
        // would mean that second request never enrols, and the box would appear to have
        // been ignored by exactly the people it was built for.
        if (!Boolean.TRUE.equals(session.getAttribute(REMEMBER_REQUESTED))) return;

        String mailbox = mailboxes.current(auth, session);
        if (mailbox == null) return;

        Optional<String> secret = credentials.secretFor(mailbox);
        if (secret.isEmpty()) return;

        tokens.enrol(DeviceCookie.parse(request).orElse(null), mailbox, secret.get(),
                DeviceLabel.of(request), ClientIp.of(request), response);
        session.setAttribute(SESSION_CHECKED, Boolean.TRUE);
    }

    private static boolean isSignedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}
