package com.example.payment_api.security;

import com.example.payment_api.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-ms}") long expirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        // Claims mínimas de propósito: "sub" (id do usuário) é o suficiente
        // para identificar o dono do recurso, e "role" o suficiente para
        // autorizar. Nada de nome, e-mail ou qualquer outro dado pessoal —
        // um JWT é apenas codificado em Base64, não criptografado, então
        // qualquer um que tenha o token consegue ler o payload.
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    // Leitura/validação do token: o método já existe, mas só passa a ser
    // chamado de verdade pelo filtro de segurança da FASE 9. Um token
    // adulterado ou expirado faz parseSignedClaims lançar uma exceção do
    // próprio jjwt (JwtException), que o filtro vai tratar então.
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirationMs() {
        return expirationMs;
    }
}
