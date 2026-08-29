package com.jarurat.mailer.security;

import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What happens in the moment after the password is accepted: the mailbox is
 * opened if the same password opens it, and the person is sent to whichever of the
 * two surfaces is theirs.
 *
 * Both halves exist because of the same complaint. Campaign Studio authenticates
 * against app_user and Stalwart authenticates against its own store, so signing in
 * used to cost one password for the console and a second one for the mailbox, on a
 * phone, before any mail appeared. In practice the two are set to the same string
 * for almost everybody, so offering the console password to the mail server once
 * turns the second prompt into a rare event rather than the normal one.
 */
@Component
public class LoginLandingHandler extends SimpleUrlAuthenticationSuccessHandler {

    /** SecurityConfig names the same parameter on the form, and this reads it back. */
    public static final String PASSWORD_PARAMETER = "password";

    private static final String MAILBOX = "/mail";
    private static final String CONSOLE = "/app";

    /**
     * A session holding only these has nothing to do in Campaign Studio, so it is
     * never sent there. Derived from Role.MAILBOX rather than written out, so
     * widening that role cannot leave this test behind.
     */
    private static final Set<String> MAIL_ONLY = Role.MAILBOX.getPermissions().stream()
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private final MailboxAccess mailboxes;

    /**
     * determineTargetUrl below is overridden outright and never calls back into the
     * superclass, so the inherited default target url and the referer and saved
     * request handling are all dead configuration here and are deliberately not set.
     * What the superclass is still carrying is the redirect strategy and the clearing
     * of the stale login exception off the session.
     */
    public LoginLandingHandler(MailboxAccess mailboxes) {
        this.mailboxes = mailboxes;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        offerPasswordToMailServer(request, authentication);
        super.onAuthenticationSuccess(request, response, authentication);
    }

    @Override
    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) {
        return landingFor(authentication == null ? null : authentication.getAuthorities(), request);
    }

    /**
     * Where this person belongs after a sign in, and the same answer PageController
     * gives on every later GET of "/" and "/app".
     *
     * Three rules in priority order. A mail-only session can only go to the mailbox.
     * Anyone without MAIL_READ can only go to the console, and that case is not
     * hypothetical: VIEWER has no mail permission at all, so sending a viewer on a
     * phone to /mail would hand them a 403 instead of a screen. Everyone else gets
     * the surface that suits the device, which is the mailbox on a phone.
     */
    public static String landingFor(Collection<? extends GrantedAuthority> authorities,
                                    HttpServletRequest request) {
        if (authorities == null || authorities.isEmpty()) return CONSOLE;
        if (!has(authorities, Permission.MAIL_READ)) return CONSOLE;
        if (isMailOnly(authorities)) return MAILBOX;
        return DeviceHints.wantsMailbox(request) ? MAILBOX : CONSOLE;
    }

    /**
     * Tested against the granted set rather than against the role name, so an
     * app_user row that an admin has put on Role.MAILBOX is treated the same way as
     * a session minted by MailboxAuthenticationProvider. Both hold exactly the same
     * rights and both belong on the same screen.
     */
    public static boolean isMailOnly(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null || authorities.isEmpty()) return false;
        for (GrantedAuthority granted : authorities) {
            if (!MAIL_ONLY.contains(granted.getAuthority())) return false;
        }
        return true;
    }

    private static boolean has(Collection<? extends GrantedAuthority> authorities, Permission permission) {
        for (GrantedAuthority granted : authorities) {
            if (permission.name().equals(granted.getAuthority())) return true;
        }
        return false;
    }

    /**
     * Offers the password that just worked to the mail server, once.
     *
     * Every failure is swallowed and none of them is logged as one, because a
     * mismatch is the ordinary case and not an error: plenty of console accounts
     * have no mailbox at all, and a mail server that is down must not turn a
     * successful console login into a failed one. MailboxAccess.openIfUnset verifies
     * against Stalwart before it stores anything, so a wrong guess here writes
     * nothing and evicts nothing.
     *
     * It has to happen here rather than in the authentication provider, and after
     * the super call would be too late for the redirect that follows. Session
     * fixation protection is configured as newSession, so the session that existed
     * while the providers ran has already been discarded along with everything
     * written to it; this handler runs against the session the browser will keep.
     */
    private void offerPasswordToMailServer(HttpServletRequest request, Authentication authentication) {
        HttpSession session = request.getSession(false);
        String address = authentication == null ? null : authentication.getName();
        String password = request.getParameter(PASSWORD_PARAMETER);
        if (session == null || address == null || password == null || password.isEmpty()) return;

        // A mail-only session has no second surface to fall back to, and it has
        // just proved this exact password to Stalwart, so it is opened on the
        // request thread. Waiting costs one round trip to a server on the same box;
        // not waiting would race the redirect and greet the one person who
        // definitely has the right password with a prompt for it. Everybody else is
        // a console session that may well have no mailbox, so their attempt runs on
        // a virtual thread and the redirect never waits for it.
        boolean mailOnly = isMailOnly(authentication.getAuthorities());

        // openIfUnset rather than open, because this offer is speculative and the
        // person's own choice outranks it. On the virtual-thread path the answer can
        // land after the redirect, after the unlock sheet has appeared and after they
        // have opened a different mailbox by hand, and open() would silently re-pin
        // the session to this one and send their next message from the wrong address.
        // The mail-only path uses it too even though its session is brand new and
        // nothing can be pinned yet, so that this handler has exactly one behaviour
        // to reason about: it offers, it never overrides.
        Runnable attempt = () -> {
            try {
                mailboxes.openIfUnset(session, address, password);
            } catch (RuntimeException ignored) {
                // Including IllegalStateException when the session went away first.
            }
        };

        if (mailOnly) attempt.run();
        else Thread.ofVirtual().name("mailbox-offer").start(attempt);
    }
}
