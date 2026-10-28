package com.example.payment_api.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Fail-fast de configuração: fora do perfil "dev", os segredos NÃO podem ter
// valor padrão no application.yml. Se JWT_SECRET/WEBHOOK_SECRET não forem
// definidos, o app tem que se recusar a subir — e não rodar silenciosamente
// com um segredo que está público no GitHub.
//
// O resolver aqui enxerga SÓ o application.yml (sem variáveis de ambiente
// da máquina), então o teste dá o mesmo resultado em qualquer computador.
class SecretsConfigurationTest {

    private PropertySourcesPropertyResolver resolver;
    private PropertySource<?> applicationYml;

    @BeforeEach
    void loadApplicationYml() throws IOException {
        applicationYml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .getFirst();

        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(applicationYml);
        resolver = new PropertySourcesPropertyResolver(sources);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jwt.secret", "webhook.secret"})
    void secret_withoutEnvironmentVariable_failsToResolve(String property) {
        String rawValue = String.valueOf(applicationYml.getProperty(property));

        assertThatThrownBy(() -> resolver.resolveRequiredPlaceholders(rawValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not resolve placeholder");
    }
}
