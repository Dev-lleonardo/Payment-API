package com.example.payment_api.controller;

import com.example.payment_api.dto.PaymentRequest;
import com.example.payment_api.dto.PaymentResponse;
import com.example.payment_api.entity.Payment;
import com.example.payment_api.exception.ErrorResponse;
import com.example.payment_api.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@Validated
@SecurityRequirement(name = "bearerAuth") // FASE 12: todos os endpoints daqui exigem JWT (ver OpenApiConfig)
@Tag(name = "Payments", description = "Criação, consulta e cancelamento de pagamentos. USER vê só os próprios; ADMIN vê todos.")
// FASE 12: 401 vale pra todos os endpoints da classe (sem token / token inválido),
// então é declarado uma vez aqui em vez de repetido em cada método.
@ApiResponse(responseCode = "401", description = "Token JWT ausente, inválido ou expirado",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // O 201 precisa ser declarado: o springdoc lê a assinatura do método, não
    // executa o código, então não enxerga o ResponseEntity.status(CREATED) e
    // documentaria 200 por padrão.
    @PostMapping
    @Operation(summary = "Cria um pagamento",
            description = "Reenviar a mesma Idempotency-Key com os mesmos dados devolve o pagamento já criado, sem duplicar.")
    @ApiResponse(responseCode = "201", description = "Pagamento criado (ou o já existente, em caso de reenvio)")
    @ApiResponse(responseCode = "400", description = "Dados inválidos ou header Idempotency-Key ausente",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency-Key já usada com dados diferentes",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody PaymentRequest request,
                                                    @Parameter(description = "Chave única gerada pelo cliente para cada tentativa de pagamento (ex.: um UUID)",
                                                            example = "3f2b8c1e-7d4a-4e5b-9c6d-1a2b3c4d5e6f")
                                                    @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                                    Authentication authentication) {
        Payment payment = paymentService.createPayment(request, idempotencyKey, currentUserId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulta um pagamento por id")
    @ApiResponse(responseCode = "200", description = "Pagamento encontrado")
    @ApiResponse(responseCode = "404", description = "Pagamento não existe ou pertence a outro usuário",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<PaymentResponse> getById(@PathVariable Long id, Authentication authentication) {
        Payment payment = paymentService.getPayment(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // @ParameterObject faz o springdoc expandir o Pageable em page/size/sort
    // separados na documentação. Sem ele, aparece como um único objeto
    // "pageable", que não é como o Spring realmente lê esses parâmetros.
    @GetMapping
    @Operation(summary = "Lista pagamentos (paginado)")
    @ApiResponse(responseCode = "200", description = "Página de pagamentos")
    public ResponseEntity<Page<PaymentResponse>> list(Authentication authentication, @ParameterObject Pageable pageable) {
        Page<PaymentResponse> page = paymentService
                .listPayments(currentUserId(authentication), isAdmin(authentication), pageable)
                .map(PaymentResponse::from);
        return ResponseEntity.ok(page);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancela um pagamento", description = "Só pagamentos PENDING ou PROCESSING podem ser cancelados.")
    @ApiResponse(responseCode = "200", description = "Pagamento cancelado")
    @ApiResponse(responseCode = "404", description = "Pagamento não existe ou pertence a outro usuário",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Transição de estado inválida (ex.: já APPROVED ou CANCELLED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<PaymentResponse> cancel(@PathVariable Long id, Authentication authentication) {
        Payment payment = paymentService.cancelPayment(id, currentUserId(authentication), isAdmin(authentication));
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // O JwtAuthenticationFilter (FASE 6) guarda o id do usuário como
    // principal e a role como authority "ROLE_<role>" — esses dois helpers
    // só leem de volta o que o filtro já colocou no SecurityContext.
    private Long currentUserId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
