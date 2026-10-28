package br.com.pixsimulado.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
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
                    "Ambiente de estudo: dados sintéticos, pagamentos exigem HTTP Basic do titular (validado pela Ledger), sem integração com o Banco Central."
            )
            .license(License().name("Uso educacional")),
    )
        // Botão "Authorize" no Swagger UI: usuário e senha do titular na Ledger.
        .components(Components().addSecuritySchemes(BASIC, SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
        .addSecurityItem(SecurityRequirement().addList(BASIC))

    private companion object {
        const val BASIC = "basicAuth"
    }
}
