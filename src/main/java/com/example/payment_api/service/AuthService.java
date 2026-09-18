package com.example.payment_api.service;

import com.example.payment_api.dto.LoginRequest;
import com.example.payment_api.dto.LoginResponse;
import com.example.payment_api.dto.RegisterRequest;
import com.example.payment_api.entity.Role;
import com.example.payment_api.entity.User;
import com.example.payment_api.exception.EmailAlreadyInUseException;
import com.example.payment_api.exception.InvalidCredentialsException;
import com.example.payment_api.repository.UserRepository;
import com.example.payment_api.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    // Sem @Transactional aqui de propósito: é uma única chamada de
    // repositório (save), e JpaRepository.save já é transacional por conta
    // própria. @Transactional só vira necessário quando várias operações
    // precisam ser atômicas juntas — vamos ver isso de verdade na FASE 7
    // (idempotência) e na FASE 6 (criação de pagamento).
    public User register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyInUseException(request.email());
        }

        // Registro público sempre cria USER. ADMIN não é algo que a própria
        // pessoa escolhe pela API — em um sistema real seria promovido
        // manualmente ou por um fluxo administrativo separado, nunca por um
        // campo "role" aceito direto do cliente no /auth/register.
        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(request.name(), request.email(), passwordHash, Role.USER);

        return userRepository.save(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user);
        return LoginResponse.bearer(token, jwtService.getExpirationMs());
    }
}
