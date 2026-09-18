package com.example.payment_api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

// Simula a verificação de assinatura de webhook que um gateway de
// pagamento real usaria (Stripe, Mercado Pago etc.): o remetente calcula um
// HMAC-SHA256 do corpo da requisição usando uma chave secreta compartilhada
// e manda o resultado num header; quem recebe recalcula e compara. Isso
// prova que quem mandou a requisição conhece o segredo — não impede que
// alguém "veja" o payload (não é criptografia), só prova autenticidade.
@Component
public class WebhookSignatureVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    public WebhookSignatureVerifier(@Value("${webhook.secret}") String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String rawBody, String providedSignatureHex) {
        if (providedSignatureHex == null || providedSignatureHex.isBlank()) {
            return false;
        }

        String expected = sign(rawBody);

        // MessageDigest.isEqual, não String.equals(): equals() retorna assim
        // que encontra o primeiro byte diferente, então o TEMPO de resposta
        // vaza quantos caracteres iniciais estavam certos — um atacante
        // poderia, em teoria, descobrir a assinatura correta byte a byte
        // medindo latência ("timing attack"). isEqual sempre compara todos
        // os bytes, em tempo constante.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignatureHex.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC signature", ex);
        }
    }
}
