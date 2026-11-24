package agency.shss7.taskflow.service;

import agency.shss7.taskflow.domain.Role;
import agency.shss7.taskflow.domain.UserAccount;
import agency.shss7.taskflow.dto.AuthDtos.*;
import agency.shss7.taskflow.repository.UserAccountRepository;
import agency.shss7.taskflow.security.JwtTokenService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthService(
        UserAccountRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtTokenService jwtTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String cleanEmail = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(cleanEmail)) {
            throw new IllegalArgumentException("An account with this email already exists");
        }
        // Self-registration always grants the standard MEMBER role; elevated roles
        // must not be self-asserted on an unauthenticated endpoint.
        UserAccount account = new UserAccount(
            cleanEmail,
            request.fullName(),
            passwordEncoder.encode(request.password()),
            Role.MEMBER
        );
        UserAccount saved = userRepository.save(account);
        String token = jwtTokenService.issueToken(saved);
        return new AuthResponse(
            saved.getId(),
            saved.getEmail(),
            saved.getFullName(),
            saved.getRole(),
            token,
            jwtTokenService.getExpirationSeconds()
        );
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String cleanEmail = request.email().trim().toLowerCase();
        UserAccount account = userRepository.findByEmailIgnoreCase(cleanEmail)
            .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        String token = jwtTokenService.issueToken(account);
        return new AuthResponse(
            account.getId(),
            account.getEmail(),
            account.getFullName(),
            account.getRole(),
            token,
            jwtTokenService.getExpirationSeconds()
        );
    }
}
