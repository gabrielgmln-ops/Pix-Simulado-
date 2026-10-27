package br.com.pixsimulado.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Configuração de acesso à Ledger API (motor de saldo).
 *
 * Todos os valores vêm de variáveis de ambiente (ver application.yml e .env.example);
 * nenhum segredo fica versionado no repositório.
 *
 * @property baseUrl URL base da Ledger, ex.: http://ledger:8080
 * @property apiKey  credencial enviada no cabeçalho de autenticação da Ledger
 * @property connectTimeout tempo máximo para abrir a conexão TCP
 * @property readTimeout    tempo máximo aguardando a resposta; em Pix, latência importa
 */
@Validated
@ConfigurationProperties(prefix = "pix.ledger")
data class LedgerProperties(
    @field:NotBlank val baseUrl: String,
    @field:NotBlank val apiKey: String,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
) {
    /** Evita que a chave de API vaze em logs caso o objeto seja impresso. */
    override fun toString(): String =
        "LedgerProperties(baseUrl=$baseUrl, apiKey=****, connectTimeout=$connectTimeout, readTimeout=$readTimeout)"
}
