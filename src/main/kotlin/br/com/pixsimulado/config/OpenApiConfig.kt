package br.com.pixsimulado.config

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Metadados da documentação OpenAPI.
 * Swagger UI: /swagger-ui.html  |  Especificação JSON: /v3/api-docs
 *
 * O esquema "basicAuth" é declarado aqui, mas exigido só nas operações que precisam
 * dele (via @SecurityRequirement): pagamentos, devoluções e escrita/listagem de chaves.
 * Consulta de uma chave e QR Code são públicos.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun pixOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Pix Simulado")
                .version("v1")
                .description(DESCRICAO)
                .license(License().name("Uso educacional")),
        )
        .components(
            Components().addSecuritySchemes(
                BASIC,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("basic")
                    .description("Usuário e senha do TITULAR na Ledger API; o Pix confere a titularidade com eles."),
            ),
        )

    /**
     * Toda resposta de erro (4xx/5xx) passa a usar o formato RFC 9457 do projeto. Assim
     * cada @ApiResponse fica só com a descrição, sem repetir o schema. O springdoc já
     * preenche esses códigos com o ProblemDetail cru do @RestControllerAdvice (no tipo de
     * mídia curinga), que não mostra o "codigo"; por isso o conteúdo é sempre substituído.
     */
    @Bean
    fun errosNoFormatoProblemDetail(): OpenApiCustomizer = OpenApiCustomizer { api ->
        ModelConverters.getInstance().read(ErroResponse::class.java).forEach { (nome, schema) ->
            api.components.addSchemas(nome, schema)
        }
        val corpoDeErro = Content().addMediaType(
            "application/problem+json",
            MediaType().schema(io.swagger.v3.oas.models.media.Schema<Any>().`$ref`("#/components/schemas/ErroResponse")),
        )
        api.paths.values
            .flatMap { it.readOperations() }
            .flatMap { it.responses.entries }
            .filter { (codigo, _) -> codigo.first() in "45" }
            .forEach { (_, resposta) -> resposta.content = corpoDeErro }
    }

    /** Formato dos erros (RFC 9457 + codigo estável). Classe só de documentação. */
    @Schema(name = "ErroResponse", description = "Problem Details (RFC 9457) com código estável para o cliente")
    data class ErroResponse(
        @Schema(example = "about:blank") val type: String,
        @Schema(example = "Regra de negócio violada") val title: String,
        @Schema(example = "422") val status: Int,
        @Schema(example = "Saldo insuficiente na conta de origem") val detail: String,
        @Schema(example = "/api/v1/pagamentos") val instance: String,
        @Schema(description = "Identificador estável do erro; trate por ele, não pelo texto", example = "SALDO_INSUFICIENTE")
        val codigo: String,
        @Schema(description = "Somente em 400 CAMPOS_INVALIDOS: um item por campo")
        val erros: List<CampoInvalido>?,
    )

    data class CampoInvalido(
        @Schema(example = "valor") val campo: String,
        @Schema(example = "numeric value out of bounds (<17 digits>.<2 digits> expected)") val mensagem: String,
    )

    companion object {
        const val BASIC = "basicAuth"

        private val DESCRICAO = """
            Serviço de pagamentos instantâneos **simulado**, inspirado no Pix, sobre a Ledger API (motor de saldo).
            Ambiente de estudo: dados sintéticos, sem integração com o Banco Central.

            **Fluxo de pagamento:** (1) `POST /api/v1/pagamentos/end-to-end-id` gera o EndToEndId;
            (2) `POST /api/v1/pagamentos` com ele. Repetir o passo 2 é seguro: devolve o mesmo resultado,
            com o cabeçalho `Idempotent-Replayed: true`. Mesmo EndToEndId com dados diferentes = 422.

            **Estados:** `LIQUIDADO` (201), `REJEITADO` (422 com `codigo`), `INCERTO` (202: a Ledger não
            respondeu; o Pix reconcilia sozinho, sem nunca debitar duas vezes).

            **Devoluções:** só o recebedor devolve, total ou parcialmente, em até 90 dias.

            **Autenticação:** pagamentos, devoluções e cadastro/exclusão/listagem de chaves exigem HTTP Basic
            do titular (o mesmo usuário da Ledger), que precisa ser dono da conta. A consulta de uma chave
            (como no DICT) e o QR Code são públicos.
        """.trimIndent()
    }
}
