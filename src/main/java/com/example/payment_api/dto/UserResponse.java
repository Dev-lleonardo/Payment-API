package com.example.payment_api.dto;

import com.example.payment_api.entity.Role;
import com.example.payment_api.entity.User;

import java.time.LocalDateTime;

// DTO de saída para o /auth/register. Não listado explicitamente no briefing
// original, mas necessário: a regra "não expor entidades JPA nos controllers"
// vale também para a resposta do registro, não só para os pagamentos.
public record UserResponse(
        Long id,
        String name,
        String email,
        Role role,
        LocalDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
