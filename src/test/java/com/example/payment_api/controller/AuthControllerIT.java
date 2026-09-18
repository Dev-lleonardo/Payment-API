package com.example.payment_api.controller;

import com.example.payment_api.AbstractIntegrationTest;
import com.example.payment_api.dto.LoginRequest;
import com.example.payment_api.dto.RegisterRequest;
import com.example.payment_api.entity.Role;
import com.example.payment_api.entity.User;
import com.example.payment_api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Passo 10.5a — primeiro pedaço dos testes de INTEGRAÇÃO via MockMvc.
//
// Diferença para os testes unitários das etapas 10.3/10.4: ali mockávamos
// os repositórios e o "self" para isolar SÓ a lógica do Service. Aqui não
// mockamos nada — subimos o contexto Spring inteiro (@SpringBootTest),
// com o filtro de segurança real, o GlobalExceptionHandler real, e um
// Postgres real via Testcontainers (herdado de AbstractIntegrationTest,
// com o schema já migrado pelo Flyway). A requisição entra por MockMvc
// simulando uma chamada HTTP real, mas sem precisar de um servidor
// escutando numa porta de verdade — é isso que @AutoConfigureMockMvc
// habilita.
//
// O que isso prova que o teste unitário de Service sozinho não prova:
// que o @RestControllerAdvice realmente traduz cada exceção de domínio
// para o status HTTP certo, que a validação de Bean Validation do
// @Valid realmente dispara antes do Service ser chamado, e que o dado
// realmente sobrevive uma volta completa pelo banco.
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ---------------------------------------------------------------
    // POST /auth/register
    // ---------------------------------------------------------------

    @Test
    void register_withValidData_createsUserAndReturns201() throws Exception {
        String email = uniqueEmail();
        RegisterRequest request = new RegisterRequest("Ada Lovelace", email, "senha12345");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Ada Lovelace"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").exists())
                // UserResponse não tem campo de senha — se algum dia alguém
                // adicionar "password" ou "passwordHash" nele por engano,
                // este teste denuncia o vazamento.
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // Não basta a resposta HTTP estar certa: confirmamos que o dado
        // realmente foi persistido, com role USER (nunca ADMIN, mesmo que
        // alguém tentasse mandar esse campo) e com a senha em hash — nunca
        // em texto puro.
        User persisted = userRepository.findByEmail(email).orElseThrow();
        assertThat(persisted.getRole()).isEqualTo(Role.USER);
        assertThat(persisted.getPasswordHash()).isNotEqualTo("senha12345");
        assertThat(passwordEncoder.matches("senha12345", persisted.getPasswordHash())).isTrue();
    }

    @Test
    void register_withDuplicateEmail_returns409() throws Exception {
        String email = uniqueEmail();
        userRepository.save(new User("Existing User", email, passwordEncoder.encode("outraSenha1"), Role.USER));

        RegisterRequest request = new RegisterRequest("Novo Nome", email, "senha12345");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email already in use: " + email))
                .andExpect(jsonPath("$.path").value("/auth/register"));
    }

    @Test
    void register_withBlankName_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("", uniqueEmail(), "senha12345");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Name is required")));
    }

    @Test
    void register_withInvalidEmailFormat_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("Nome Valido", "nao-e-um-email", "senha12345");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Email must be a valid email address")));
    }

    @Test
    void register_withShortPassword_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest("Nome Valido", uniqueEmail(), "1234567");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Password must be at least 8 characters long")));
    }

    // ---------------------------------------------------------------
    // POST /auth/login
    // ---------------------------------------------------------------

    @Test
    void login_withValidCredentials_returns200WithToken() throws Exception {
        String email = uniqueEmail();
        userRepository.save(new User("Login User", email, passwordEncoder.encode("senha12345"), Role.USER));

        LoginRequest request = new LoginRequest(email, "senha12345");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.expiresInMs").isNumber());
    }

    @Test
    void login_withWrongPassword_returns401WithGenericMessage() throws Exception {
        String email = uniqueEmail();
        userRepository.save(new User("Login User", email, passwordEncoder.encode("senhaCorreta1"), Role.USER));

        LoginRequest request = new LoginRequest(email, "senhaErrada99");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void login_withNonexistentEmail_returns401WithSameGenericMessage() throws Exception {
        // Mesma mensagem do teste acima, de propósito: é exatamente o
        // design anti-enumeração do AuthService.login() sendo validado
        // ponta a ponta — a API nunca revela se o e-mail existe ou se foi
        // a senha que errou.
        LoginRequest request = new LoginRequest(uniqueEmail(), "qualquerSenha1");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void login_withBlankFields_returns400() throws Exception {
        LoginRequest request = new LoginRequest("", "");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Email is required")))
                .andExpect(jsonPath("$.message", containsString("Password is required")));
    }

    private String uniqueEmail() {
        return "auth-it-" + UUID.randomUUID() + "@example.com";
    }
}
