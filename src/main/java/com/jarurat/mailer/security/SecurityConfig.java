package com.jarurat.mailer.security;

import com.jarurat.mailer.device.DeviceSettings;
import com.jarurat.mailer.device.DeviceTokenService;
import com.jarurat.mailer.device.PersistentDeviceFilter;
import com.jarurat.mailer.mail.MailCredentialStore;
import com.jarurat.mailer.repositories.ApiKeyRepository;
import com.jarurat.mailer.webmail.MailboxAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsChecker;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize on controller methods
public class SecurityConfig {

    /** Links inside already-delivered email must keep working, so these stay open. */
    static final String[] PUBLIC_PATHS = {
            "/", "/login", "/logout-success", "/error",
            // The 429 page. It is reached by a forward, which is not filtered, so
            // this entry is only what keeps a direct visit from bouncing to the login
            // page. There is nothing on it but a sentence and a link back.
            TooManyRequestsPage.PATH,
            "/css/**", "/js/**", "/logo.png", "/logo1.png", "/favicon.ico",
            // The install card has to be offered on the login page, before anyone
            // has an account, so the whole PWA surface is public. None of it is
            // user data: a manifest, an icon set, a worker and an offline card.
            // The worker must be served from the root or its scope cannot cover "/".
            "/manifest.webmanifest", "/sw.js", "/offline.html", "/icons/**",
            "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png",
            "/api/mailer/click", "/api/mailer/open", "/api/mailer/unsubscribe",
            "/api/mailer/success",
            // Amazon posts here from addresses we cannot predict, so it has to be
            // open. It is not unauthenticated: SnsMessageVerifier proves the
            // payload was signed by Amazon and names one of our own topics before
            // any field of it is read.
            "/api/sns/ses-events"
    };

    /**
     * The login form's address field, named once because two things now read it: the
     * authentication filter, and the rate limiter in front of it. A limiter reading a
     * field name that had drifted would key every attempt on an empty string and stop
     * counting per address without failing anywhere visible.
     */
    static final String USERNAME_PARAMETER = "email";

    /** The path the form posts to, and the only path the limiter looks at. */
    static final String LOGIN_PATH = "/login";

    /**
     * Everything a session bought with a mailbox password may touch. Everything else
     * behind the login is console surface and is closed to it below.
     *
     * "/app" is on the list and has to be, because PageController answers it with a
     * redirect to "/mail" for these sessions. Denying it here would swap that redirect
     * for a 403 on the one link the console rail and the phone account sheet both
     * offer.
     *
     * "/api/devices/**" is here for a reason worth spelling out. A device token is
     * minted almost entirely for mail-only sessions on phones, so the person who most
     * needs to see their devices and sign a lost one out is exactly the person with no
     * app_user row and no console permission. Leaving it to the console rule below
     * would answer them 403 on the only control that withdraws a credential lasting
     * months. It exposes nothing else: DeviceApi resolves the mailbox from the session
     * pin rather than from a parameter, and DeviceTokenService checks the ownership of
     * a family again before it deletes one.
     */
    static final String[] MAIL_ONLY_PATHS = { "/mail", "/api/mail/**", "/api/devices/**", "/app", "/logout" };

    /**
     * Every permission that is not one of the two a mailbox password buys. Derived
     * rather than listed, so a permission added to the enum is console surface by
     * default and has to be put on Role.MAILBOX deliberately to become anything else.
     */
    private static String[] consolePermissions() {
        return Arrays.stream(Permission.values())
                .filter(p -> !Role.MAILBOX.can(p))
                .map(Enum::name)
                .toArray(String[]::new);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Two providers, in this order, on one manager.
     *
     * The order is the security contract, not a preference. DaoAuthenticationProvider
     * runs first so a real console credential always wins, and ProviderManager returns
     * on the first provider that produces a result, so the mailbox provider is not even
     * called when it does. When the DAO provider throws instead, ProviderManager rethrows
     * AccountStatusException immediately and only lets a plain AuthenticationException
     * fall through to the next provider, which is what keeps a disabled account from
     * reaching the mail server at all. A lock is deliberately not in that category;
     * consolePreChecks below is where that is decided and why.
     *
     * The manager is built by hand rather than left to the auto-configuration because
     * the auto-configured one registers a single DAO provider and offers no way to put a
     * second provider after it. Both providers are constructed here rather than injected,
     * because an AuthenticationProvider bean anywhere in the context is taken over by
     * InitializeAuthenticationProviderBeanManagerConfigurer and becomes the entire global
     * AuthenticationManager, displacing the DAO provider there.
     *
     * The event publisher then has to be set here too. A hand-built ProviderManager
     * publishes to a null publisher by default, so every login event would vanish
     * silently, and LoginAttemptListener listens for exactly those events: without
     * that line the lockout, the last-login stamp and the login audit trail would all
     * stop working with nothing on the screen or in the log to say so.
     *
     * Two things are registered on that publisher and on the DAO provider below, and
     * neither is decoration. Read consolePreChecks and the exception mapping together
     * before changing either.
     */
    @Bean
    public AuthenticationManager authenticationManager(AppUserDetailsService users,
                                                       PasswordEncoder passwordEncoder,
                                                       MailboxAccess mailboxes,
                                                       ObjectProvider<AuthenticationEventPublisher> publishers,
                                                       ApplicationEventPublisher events) {
        DaoAuthenticationProvider dao = new DaoAuthenticationProvider(users);
        dao.setPasswordEncoder(passwordEncoder);
        dao.setPreAuthenticationChecks(consolePreChecks());

        ProviderManager manager = new ProviderManager(
                List.of(dao, new MailboxAuthenticationProvider(mailboxes)));

        AuthenticationEventPublisher publisher =
                publishers.getIfAvailable(() -> new DefaultAuthenticationEventPublisher(events));
        registerMailboxFailureEvent(publisher);
        manager.setAuthenticationEventPublisher(publisher);
        return manager;
    }

    /**
     * The account-status checks the console provider runs before it compares a
     * password, with the lock reported as a credentials failure rather than an
     * account-status one.
     *
     * That single change is what separates console lockout from mail availability.
     * ProviderManager rethrows any AccountStatusException the instant a provider
     * raises it and never consults the providers after it, so the stock
     * LockedException took the mailbox down with the console: five wrong guesses at a
     * named staff address left that person unable to read their own mail for fifteen
     * minutes, and let an unauthenticated attacker hold them out of webmail
     * indefinitely at five requests per fifteen minutes. Mail is the surface most of
     * the organisation has, and a console control must not be able to switch it off.
     *
     * Nothing is loosened on the console side, and the ordering is why. This checker
     * runs before the password is compared, so a locked account is refused by
     * DaoAuthenticationProvider whatever arrives with it. What follows the refusal is
     * MailboxAuthenticationProvider, which reads no app_user row and can mint nothing
     * but Role.MAILBOX, so the most a locked console account can now obtain is the
     * mailbox its own mail password already opens in any IMAP client. Guessing at
     * that password is bounded by LoginRateLimiter, which is the control that belongs
     * on it, and that control now slows an over-budget address down rather than
     * refusing it. The distinction is the whole reason this method exists: a refusal
     * keyed on somebody's address is a way to hold that person out of their own mail,
     * which is the harm this method was written to remove, and for a while the
     * limiter was quietly putting it back.
     *
     * Disabled and expired accounts keep the stock behaviour and still stop the login
     * dead. Deactivating somebody is meant to end their access, not move it.
     *
     * THE ORDER OF THESE FOUR TESTS IS THE SECOND HALF OF THAT SENTENCE, and getting
     * it wrong is how the paragraph above came to be false for eight days. The stock
     * checker tests the lock first, so delegating to it after the lock test meant an
     * account that was both locked and deactivated raised ConsoleLockedException, the
     * lock case, and fell through to the mailbox provider: a person who had been
     * deactivated could still sign in to their mail for the length of the lock window.
     * It needed no unusual state to reach, because AdminApi clears lockedUntil only
     * when activating somebody, so the ordinary incident-response sequence, an account
     * being guessed at until it locks and then deactivated, leaves exactly that pair
     * set. Measured on the running application: deactivated and locked, correct
     * mailbox password, 302 to /mail; deactivated alone, 302 to /login?error.
     *
     * So the two AccountStatusExceptions that mean the account is finished are raised
     * first, and only an account that is otherwise in good standing gets the lock
     * treated as a credentials failure. All four tests are written out rather than
     * delegated for the last one, because the stock checker offers no way to run a
     * subset in a different order, and a checker that ran the lock test twice would be
     * the same defect with more code. The messages are plain strings rather than the
     * stock message source: nothing renders them, since the login page deliberately
     * shows one generic sentence for every kind of failure.
     */
    private static UserDetailsChecker consolePreChecks() {
        return user -> {
            if (!user.isEnabled()) {
                throw new DisabledException("User is disabled");
            }
            if (!user.isAccountNonExpired()) {
                throw new AccountExpiredException("User account has expired");
            }
            if (!user.isAccountNonLocked()) {
                throw new ConsoleLockedException("Bad credentials");
            }
            // Kept in the pre-checks, where the stock checker had it, rather than left
            // to the provider's own post-authentication check. Moving it would change
            // when it fires relative to the password comparison for no reason anybody
            // asked for.
            if (!user.isCredentialsNonExpired()) {
                throw new CredentialsExpiredException("User credentials have expired");
            }
        };
    }

    /**
     * Teaches the publisher that a mailbox failure is still a bad-credentials failure.
     *
     * DefaultAuthenticationEventPublisher looks its event constructor up by the
     * exception's exact class name and falls back to a default constructor that is
     * null unless somebody sets one, so a subclass it has never heard of publishes no
     * event whatsoever. MailboxAuthenticationProvider throws such a subclass, and it
     * is the last provider on the manager, so without this line the failure event
     * would simply stop arriving and the lockout counter, the last-login stamp and
     * every LOGIN_FAILED audit row would go quiet with nothing to say they had. It is
     * a merge rather than a replacement, so the stock mappings are untouched.
     *
     * The publisher is normally the shared bean Spring Boot registers, and mutating it
     * from here is deliberate: it is the same publisher every chain in the context
     * uses, and the mapping should hold for all of them. If it is ever replaced by
     * something that is not a DefaultAuthenticationEventPublisher this quietly does
     * nothing, which is why MailboxAuthenticationProviderTest asserts the event.
     */
    private static void registerMailboxFailureEvent(AuthenticationEventPublisher publisher) {
        if (publisher instanceof DefaultAuthenticationEventPublisher defaults) {
            defaults.setAdditionalExceptionMappings(Map.of(
                    MailboxBadCredentialsException.class, AuthenticationFailureBadCredentialsEvent.class,
                    ConsoleLockedException.class, AuthenticationFailureBadCredentialsEvent.class));
        }
    }

    /**
     * Machine callers. Stateless, API key only, no CSRF because there is no
     * browser session to ride on.
     *
     * DO NOT ADD A PASSWORD-BASED AUTHENTICATION MECHANISM TO THIS CHAIN. The
     * authenticationManager bean above is declared as a bean, and Spring Boot's
     * HttpSecurityConfiguration autowires any AuthenticationManager bean and installs
     * it as the default on every HttpSecurity, this one included. Nothing invokes it
     * here today, because ApiKeyAuthFilter sets the SecurityContext itself and the
     * chain never reaches an authentication filter. The moment somebody adds
     * httpBasic() or a form login to this chain, that default manager wakes up and
     * every mailbox password in the organisation becomes a valid credential for
     * /api/v1/**, which is anyRequest().authenticated() with no permission rule
     * behind it at all. If this chain ever needs to check a password, give it its own
     * manager holding only the DAO provider, by calling .authenticationManager(...)
     * explicitly.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http, ApiKeyRepository apiKeyRepository) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyRepository), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** The human console. */
    @Bean
    @Order(2)
    public SecurityFilterChain consoleChain(HttpSecurity http,
                                            AuthenticationManager authenticationManager,
                                            LoginLandingHandler loginLandingHandler,
                                            LoginRateLimiter loginRateLimiter,
                                            MailboxAccess mailboxes,
                                            DeviceSettings deviceSettings,
                                            DeviceTokenService deviceTokens,
                                            MailCredentialStore mailCredentials,
                                            AppUserDetailsService consoleUsers) throws Exception {
        // Constructed here rather than annotated, because Spring Boot also registers
        // every Filter bean with the servlet container: an @Component on it would put
        // it in front of the stateless API chain as well and run it twice on this one.
        PersistentDeviceFilter persistentDevice =
                new PersistentDeviceFilter(deviceSettings, deviceTokens, mailboxes, mailCredentials,
                        consoleUsers);
        // The default XOR handler expects a BREACH-encoded value, which a fetch()
        // reading the raw cookie cannot produce. The plain handler matches what
        // the browser actually sends back.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // resolve eagerly so the cookie is always set

        // failureUrl("/login?error") builds exactly this, except that its handler is
        // left with allowSessionCreation on, so every failed login minted a fresh
        // session with the eight-hour timeout in application.properties. That turned
        // one unauthenticated POST into a permanent allocation and gave anybody
        // guessing passwords a free way to grow the session store alongside the
        // guessing. Nothing needs the session: the exception it saves under
        // SPRING_SECURITY_LAST_EXCEPTION is read by no template, because the login
        // page shows one generic sentence for every kind of failure on purpose. The
        // redirect target is unchanged, so the /login?error contract is untouched.
        SimpleUrlAuthenticationFailureHandler failureHandler =
                new SimpleUrlAuthenticationFailureHandler("/login?error");
        failureHandler.setAllowSessionCreation(false);

        http
                .authenticationManager(authenticationManager)
                // Before the authentication filter and after the CSRF filter, so a
                // request that never carried a token cannot run up somebody else's
                // counter. This is the only thing standing between /login and
                // unlimited guessing at mailbox passwords, most of which belong to
                // people with no app_user row and therefore no account lockout.
                .addFilterBefore(
                        new LoginRateLimitFilter(loginRateLimiter, LOGIN_PATH, USERNAME_PARAMETER),
                        UsernamePasswordAuthenticationFilter.class)
                // After SecurityContextHolderFilter, which is where the session is read,
                // and before the authorization rules, which is where the absence of a
                // session turns into a redirect to the login page. That is the whole
                // window this filter has: it must see that there is no authentication
                // and put one back before anything acts on the emptiness. Registering it
                // any earlier would run it before the session had been consulted at all,
                // and it would rotate a device token on every request of an already
                // signed-in phone.
                .addFilterBefore(persistentDevice, UsernamePasswordAuthenticationFilter.class)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        // Gmail and Yahoo one-click unsubscribe posts from the mail
                        // client, so it cannot carry a browser token. The token in the
                        // URL is the capability being checked.
                        // Amazon cannot carry our CSRF token either. The signature
                        // check is what stands in for it, and it is strictly stronger.
                        .ignoringRequestMatchers("/api/mailer/unsubscribe", "/api/sns/ses-events"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(MAIL_ONLY_PATHS).authenticated()
                        // Defence in depth for the one-login boundary. Every console
                        // endpoint already carries its own @PreAuthorize, but the
                        // authorities a mailbox password grants are now the weakest
                        // credential in the building, and "authenticated" stopped
                        // meaning "has an app_user row" the moment that became
                        // possible. This closes the whole console surface to a
                        // mail-only session in one rule instead of trusting every
                        // controller to have remembered its annotation, and it already
                        // catches one that did not: GET /api/overview has no
                        // annotation, and answers with the SES account and domain
                        // health of the organisation.
                        .anyRequest().access(AuthorityAuthorizationManager.hasAnyAuthority(consolePermissions())))
                .formLogin(form -> form
                        .loginPage(LOGIN_PATH)
                        .loginProcessingUrl(LOGIN_PATH)
                        .usernameParameter(USERNAME_PARAMETER)
                        .passwordParameter(LoginLandingHandler.PASSWORD_PARAMETER)
                        // Not defaultSuccessUrl: there are two landing pages now, the
                        // choice depends on the session and the device, and the same
                        // handler is the only place allowed to offer this password to
                        // the mail server.
                        .successHandler(loginLandingHandler)
                        .failureHandler(failureHandler)
                        .permitAll())
                .logout(logout -> logout
                        // Sign out has to mean the mailbox password is gone from this
                        // process, which is what MailboxAccess.close says it is for
                        // and what nothing on this path was calling. Invalidating the
                        // session drops this browser's pin on the mailbox but leaves
                        // the plaintext secret resident in MailCredentialStore for the
                        // life of the JVM, so the button labelled Sign out was the one
                        // control that did not clear it. Registered here rather than
                        // through deleteCookies or the success handler because
                        // LogoutConfigurer appends its own SecurityContextLogoutHandler
                        // after everything added this way, and that handler is what
                        // invalidates the session; reading the mailbox off the session
                        // has to happen first.
                        .addLogoutHandler((request, response, authentication) ->
                                mailboxes.close(authentication, request.getSession(false)))
                        // And the same promise about the copy at rest. Sign out has to
                        // revoke the device token as well, or the button would drop the
                        // password from the heap and leave a cookie on the phone that
                        // silently signs it straight back in on the next request. The
                        // row is deleted rather than expired, which destroys the only
                        // copy of the sealed mailbox password with it.
                        .addLogoutHandler((request, response, authentication) ->
                                deviceTokens.forget(request, response))
                        // Sign out is a plain link in the rail, in the phone account
                        // sheet and in the console, which the UI specification pins
                        // down as markup. LogoutConfigurer answers a CSRF-enabled chain
                        // with a POST-only matcher, so those links were not logging
                        // anybody out: the request slid past LogoutFilter, past the
                        // authorization rules as an already-signed-in user, and into a
                        // 404 for a path with no controller behind it. Accepting GET as
                        // well is what makes the link work. The cost is that a hostile
                        // page can force a sign out with an image tag, which loses an
                        // unsaved draft and nothing else; a sign out that silently does
                        // nothing is the worse of the two.
                        .logoutRequestMatcher(new OrRequestMatcher(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/logout"),
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/logout")))
                        .logoutSuccessUrl("/login?loggedOut")
                        // JCFSESSION, not JSESSIONID: application.properties renames
                        // the session cookie, so the old entry here cleared a cookie
                        // that has never existed in this application and read as
                        // protection while doing nothing.
                        .deleteCookies("JCFSESSION", "XSRF-TOKEN")
                        .invalidateHttpSession(true)
                        .permitAll())
                .sessionManagement(session -> session
                        .sessionFixation(sf -> sf.newSession())
                        .maximumSessions(5))
                .headers(headers -> headers
                        .frameOptions(f -> f.sameOrigin())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                // The composer previews arbitrary customer HTML in a sandboxed
                                // iframe, so frame-src has to allow blob/data.
                                "default-src 'self'; " +
                                "img-src 'self' data: https:; " +
                                "style-src 'self' 'unsafe-inline'; " +
                                "script-src 'self' 'unsafe-inline'; " +
                                "frame-src 'self' blob: data:; " +
                                "object-src 'none'; base-uri 'self'; form-action 'self'")))
                // API callers should get a 401; browsers should get the login page.
                // Both mappings are required: with a single mapping registered,
                // Spring applies it to every request and ignores the matcher.
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                PathPatternRequestMatcher.withDefaults().matcher("/**")));
        return http.build();
    }
}
