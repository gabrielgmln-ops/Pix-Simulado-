package br.com.pixsimulado.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Metadados da documentação OpenAPI.
 * Swagger UI: /swagger-ui.html  |  Especificação JSON: /v3/api-docs
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun pixOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Pix Simulado")
            .version("v1")
            .description(
                "Serviço de pagamentos instantâneos simulado. Usa a Ledger API como motor de saldo. " +
                    "Ambiente de estudo: dados sintéticos, sem autenticação e sem integração com o Banco Central."
            )
            .license(License().name("Uso educacional")),
    )
}
