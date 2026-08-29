package com.jarurat.mailer.device;

import com.jarurat.mailer.mail.MailCredentialStore;
import com.jarurat.mailer.mail.MailException;
import com.jarurat.mailer.security.MailboxUserDetails;
import com.jarurat.mailer.security.Permission;
import com.jarurat.mailer.security.Role;
import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the filter does to a request, in the two directions it works in, and the four
 * ways it is allowed to fail.
 *
 * The failure cases carry the weight. A remember-me filter that throws takes the
 * login page down with it, and one that authenticates somebody whose mailbox did not
 * open leaves them looking at a mail screen asking for a password they have no reason
 * to expect. Both of those are worse than the eight hour session this replaces, so
 * every one of them is asserted rather than assumed.
 */
class PersistentDeviceFilterTest {

    private static final String MAILBOX = "priya@jarurat.care";
    private static final String PASSWORD = "the-mailbox-password";

    private DeviceTokenService tokens;
    private MailboxAccess mailboxes;
    private MailCredentialStore credentials;
    private PersistentDeviceFilter filter;

    @BeforeEach
    void wire() {
        tokens = mock(DeviceTokenService.class);
        mailboxes = mock(MailboxAccess.class);
        credentials = mock(MailCredentialStore.class);
        filter = new PersistentDeviceFilter(
                new DeviceSettings(true, true, 180, 60, 12), tokens, mailboxes, credentials);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a good cookie signs the phone in and opens its mailbox in the same breath")
    void aValidCookieRestoresBothHalves() throws Exception {
        when(tokens.restore(any(), anyString(), any()))
                .thenReturn(Optional.of(new DeviceTokenService.Restored(MAILBOX, PASSWORD)));

        MockHttpServletRequest request = requestWithCookie("/mail");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isInstanceOf(MailboxUserDetails.class);
        assertThat(auth.getName()).isEqualTo(MAILBOX);

        // The mailbox is opened with the decrypted password, not merely assumed to be
        // open, so the screen this lands on has mail on it.
        verify(mailboxes).open(any(), eq(MAILBOX), eq(PASSWORD));

        // And the session survives the request, or the next one would restore again and
        // rotate the token a second time.
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("SPRING_SECURITY_CONTEXT"))
                .isInstanceOf(SecurityContext.class);
    }

    @Test
    @DisplayName("a restored session is worth the mailbox and nothing in Campaign Studio")
    void aRestoredSessionCarriesOnlyMailboxAuthorities() throws Exception {
        when(tokens.restore(any(), anyString(), any()))
                .thenReturn(Optional.of(new DeviceTokenService.Restored(MAILBOX, PASSWORD)));

        filter.doFilter(requestWithCookie("/mail"), new MockHttpServletResponse(), new MockFilterChain());

        List<String> granted = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(granted).containsExactlyInAnyOrderElementsOf(
                Role.MAILBOX.getPermissions().stream().map(Enum::name).toList());
        assertThat(granted).doesNotContain(Permission.CAMPAIGNS_SEND.name(), Permission.TEAM_WRITE.name());
    }

    @Test
    @DisplayName("a mailbox that will not open leaves nobody signed in, and the device is dropped")
    void aRefusedMailboxIsNotAHalfSession() throws Exception {
        when(tokens.restore(any(), anyString(), any()))
                .thenReturn(Optional.of(new DeviceTokenService.Restored(MAILBOX, PASSWORD)));
        when(mailboxes.open(any(), anyString(), anyString()))
                .thenThrow(new MailException(MailException.Kind.AUTH, "That password was not accepted."));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(requestWithCookie("/mail"), response, new MockFilterChain());

        // Authenticating here would produce exactly the state this must never reach: a
        // session with mail permissions and no mailbox behind it.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokens).forget(any(), any());
    }

    @Test
    @DisplayName("no cookie, a refused token, or a database that is down all end at the login page")
    void everyFailureIsSilent() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/mail"), new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokens, never()).restore(any(), anyString(), any());

        when(tokens.restore(any(), anyString(), any())).thenReturn(Optional.empty());
        filter.doFilter(requestWithCookie("/mail"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // A database that is unreachable must not turn every page of the application
        // into a stack trace, so the request continues exactly as if no cookie existed.
        when(tokens.restore(any(), anyString(), any())).thenThrow(new IllegalStateException("no database"));
        MockFilterChain reached = new MockFilterChain();
        filter.doFilter(requestWithCookie("/mail"), new MockHttpServletResponse(), reached);
        assertThat(reached.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("a signed-in phone with its mailbox open is enrolled once, not once per request")
    void aPhoneIsEnrolled() throws Exception {
        signIn(mailOnly());
        when(mailboxes.current(any(), any())).thenReturn(MAILBOX);
        when(credentials.secretFor(MAILBOX)).thenReturn(Optional.of(PASSWORD));

        // Two requests of one session rather than one request filtered twice, because
        // OncePerRequestFilter would skip the second call on the same request object
        // and the test would pass without proving anything.
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = phone("/mail");
            request.setSession(session);
            // The consent the sign-in form carries. Enrolment is opt in, so the first
            // request has to bring it or nothing is sealed; the second deliberately does
            // not, proving the choice is remembered rather than re-asked.
            if (i == 0) request.setParameter("remember", "true");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        verify(tokens).enrol(any(), eq(MAILBOX), eq(PASSWORD), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a phone that did not ask to stay signed in is never enrolled")
    void withoutConsentNothingIsSealed() throws Exception {
        signIn(mailOnly());
        when(mailboxes.current(any(), any())).thenReturn(MAILBOX);
        when(credentials.secretFor(MAILBOX)).thenReturn(Optional.of(PASSWORD));

        // Everything an enrolment needs is present except the one thing that makes it
        // somebody's decision. Clearing that box on a shared machine has to mean what it
        // says, or the control is decoration and the copy beside it is a false promise.
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = phone("/mail");
            request.setSession(session);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        verify(tokens, never()).enrol(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("consent given on a later request still enrols, because the mailbox opens after the login")
    void consentArrivingLateStillEnrols() throws Exception {
        signIn(mailOnly());
        when(mailboxes.current(any(), any())).thenReturn(MAILBOX);
        when(credentials.secretFor(MAILBOX)).thenReturn(Optional.of(PASSWORD));

        // Somebody whose console and mailbox passwords differ ticks the box on the
        // sign-in form and supplies the mailbox password later through the unlock sheet.
        // If the first request without a credential settled the session, that second
        // request would never seal anything and the box would look ignored by exactly
        // the people it exists for.
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest first = phone("/mail");
        first.setSession(session);
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest unlock = phone("/api/mail/unlock");
        unlock.setSession(session);
        unlock.setParameter("remember", "true");
        filter.doFilter(unlock, new MockHttpServletResponse(), new MockFilterChain());

        verify(tokens).enrol(any(), eq(MAILBOX), eq(PASSWORD), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a laptop running the console is left on the eight hour session")
    void aConsoleSessionIsNotEnrolled() throws Exception {
        signIn(new SimpleGrantedAuthority(Permission.CAMPAIGNS_SEND.name()),
                new SimpleGrantedAuthority(Permission.MAIL_READ.name()));
        when(mailboxes.current(any(), any())).thenReturn(MAILBOX);
        when(credentials.secretFor(MAILBOX)).thenReturn(Optional.of(PASSWORD));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app");
        request.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126");
        request.setSession(new MockHttpSession());
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        // A restored session holds Role.MAILBOX only. Enrolling this one would sign
        // somebody back in tomorrow with less than they had, and PageController would
        // bounce them from /app to /mail with no way back except signing out.
        verify(tokens, never()).enrol(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("the login post is left alone, and so is the static surface")
    void thePathsThatAreSkipped() throws Exception {
        MockHttpServletRequest login = requestWithCookie("/login");
        login.setMethod("POST");
        filter.doFilter(login, new MockHttpServletResponse(), new MockFilterChain());

        filter.doFilter(requestWithCookie("/css/app.css"), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(requestWithCookie("/api/mailer/open"), new MockHttpServletResponse(), new MockFilterChain());

        verify(tokens, never()).restore(any(), anyString(), any());
    }

    @Test
    @DisplayName("with the feature switched off the filter does nothing at all")
    void theKillSwitch() throws Exception {
        PersistentDeviceFilter off = new PersistentDeviceFilter(
                new DeviceSettings(false, true, 180, 60, 12), tokens, mailboxes, credentials);

        off.doFilter(requestWithCookie("/mail"), new MockHttpServletResponse(), new MockFilterChain());

        verify(tokens, never()).restore(any(), anyString(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static MockHttpServletRequest requestWithCookie(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setCookies(new Cookie(DeviceCookie.NAME,
                DeviceCookie.valueOf(DeviceCookie.mintSelector(), DeviceCookie.mintSecret())));
        return request;
    }

    private static MockHttpServletRequest phone(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Sec-CH-UA-Mobile", "?1");
        request.addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) Safari/605");
        return request;
    }

    private static GrantedAuthority[] mailOnly() {
        return Role.MAILBOX.getPermissions().stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                .toArray(GrantedAuthority[]::new);
    }

    private static void signIn(GrantedAuthority... authorities) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                MAILBOX, null, List.of(authorities)));
        SecurityContextHolder.setContext(context);
    }
}
