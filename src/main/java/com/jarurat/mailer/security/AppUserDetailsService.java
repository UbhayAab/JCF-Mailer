package com.jarurat.mailer.security;

import com.jarurat.mailer.repositories.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The lookup key comes from LoginAddress rather than from a trim and a lower case
     * written out here, so this file, MailboxAuthenticationProvider and the counter in
     * LoginRateLimiter cannot disagree about what an address is. They did, and the
     * disagreement was a way past the limiter. The default-locale toLowerCase this
     * used to call was a second, quieter version of the same fault: under a Turkish
     * locale it folds "I" to a dotless i and looks up an address nobody typed.
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(LoginAddress.canonical(email))
                .map(AppUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));
    }
}
