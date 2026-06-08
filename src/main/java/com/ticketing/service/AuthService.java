package com.ticketing.service;

import com.ticketing.config.JwtProperties;
import com.ticketing.domain.Role;
import com.ticketing.domain.User;
import com.ticketing.dto.auth.AuthResponse;
import com.ticketing.dto.auth.LoginRequest;
import com.ticketing.dto.auth.RegisterRequest;
import com.ticketing.exception.ConflictException;
import com.ticketing.repository.UserRepository;
import com.ticketing.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login. The service never returns entities — only the
 * {@link AuthResponse} DTO — and never stores raw passwords.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final long expirationSeconds;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.expirationSeconds = jwtProperties.expirationMinutes() * 60;
    }

    /**
     * Registers a new USER. The unique email is enforced both here (friendly 409)
     * and by the DB constraint (race-safe backstop).
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("An account with that email already exists");
        }

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                Role.USER  // self-registration is always USER; admins are seeded/promoted
        );
        user = userRepository.save(user);
        log.info("Registered new user id={} email={}", user.getId(), user.getEmail());

        return toAuthResponse(user);
    }

    /**
     * Authenticates credentials via the AuthenticationManager (which uses our
     * UserDetailsService + BCrypt), then issues a token. A wrong email or password
     * surfaces as {@code BadCredentialsException} → 401, with no hint as to which
     * was wrong (avoids user enumeration).
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user vanished"));
        log.info("User id={} logged in", user.getId());
        return toAuthResponse(user);
    }

    private AuthResponse toAuthResponse(User user) {
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
        return AuthResponse.bearer(
                token,
                expirationSeconds,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name()
        );
    }
}
