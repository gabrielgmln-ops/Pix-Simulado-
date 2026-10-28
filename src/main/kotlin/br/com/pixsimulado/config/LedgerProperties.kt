package br.com.pixsimulado.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
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
 * O Pix se autentica na Ledger por HTTP Basic como um usuário técnico de papel
 * OPERADOR: é a instituição movimentando as contas dos próprios clientes, como um
 * PSP faz no arranjo Pix. A titularidade de quem paga é responsabilidade do Pix.
 *
 * @property baseUrl URL base da Ledger, ex.: http://localhost:8081
 * @property usuario usuário técnico na Ledger (LEDGER_USUARIO)
 * @property senha   senha desse usuário (LEDGER_SENHA)
 * @property connectTimeout tempo máximo para abrir a conexão TCP
 * @property readTimeout    tempo máximo aguardando a resposta; estourou = resultado INCERTO
 * @property reconciliacao  reenvio automático dos pagamentos com resultado incerto
 */
@Validated
@ConfigurationProperties(prefix = "pix.ledger")
data class LedgerProperties(
    @field:NotBlank val baseUrl: String,
    @field:NotBlank val usuario: String,
    @field:NotBlank val senha: String,
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
    @field:Valid val reconciliacao: Reconciliacao = Reconciliacao(),
) {
    /**
     * @property habilitada liga o agendamento (desligado nos testes, que disparam à mão)
     * @property intervalo  pausa entre rodadas e idade mínima de um INCERTO para reenvio
     * @property maxTentativas acima disso o pagamento fica INCERTO para análise manual
     * @property lote quantos pagamentos são reprocessados por rodada
     */
    data class Reconciliacao(
        val habilitada: Boolean = true,
        val intervalo: Duration = Duration.ofSeconds(30),
        @field:Min(1) val maxTentativas: Int = 10,
        @field:Min(1) val lote: Int = 50,
    )

    /** Evita que a senha vaze em logs caso o objeto seja impresso. */
    override fun toString(): String =
        "LedgerProperties(baseUrl=$baseUrl, usuario=$usuario, senha=****, " +
            "connectTimeout=$connectTimeout, readTimeout=$readTimeout, reconciliacao=$reconciliacao)"
}
