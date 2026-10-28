package br.com.pixsimulado.ledger

import br.com.pixsimulado.config.LedgerProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.http.HttpClient
import java.util.UUID

/** Pedido de transferência no contrato da Ledger (POST /api/v1/transferencias). */
data class TransferenciaLedger(
    val contaOrigemId: UUID,
    val contaDestinoId: UUID,
    val valor: BigDecimal,
    val descricao: String?,
)

/**
 * Resultado de uma chamada à Ledger, já classificado para o Pix decidir o estado.
 * A distinção que importa é entre [Recusada] (certeza de que nada foi debitado)
 * e [Indeterminada] (pode ter debitado ou não; só a repetição idempotente responde).
 */
sealed interface ResultadoLedger {
    /** 2xx: a Ledger gravou a transferência (ou devolveu a gravada, numa repetição). */
    data class Efetivada(val transacaoId: UUID) : ResultadoLedger

    /** 4xx de negócio/validação: a Ledger recusou e não movimentou nada. */
    data class Recusada(val codigo: String, val mensagem: String) : ResultadoLedger

    /** Timeout, falha de rede, 401, 5xx ou resposta inesperada: resultado desconhecido. */
    data class Indeterminada(val motivo: String) : ResultadoLedger
}

/** Resposta da Ledger à pergunta "este cliente pode usar esta conta?". */
enum class AcessoConta { PERMITIDO, NAO_AUTENTICADO, NEGADO, INDISPONIVEL }

/**
 * Cliente HTTP da Ledger API.
 *
 * Nunca lança exceção para o chamador: todo desfecho vira um [ResultadoLedger], porque
 * em pagamentos o erro de rede não é "exceção", é um estado de negócio (INCERTO) que
 * precisa ser gravado e reconciliado.
 */
@Component
class LedgerClient(
    props: LedgerProperties,
    private val json: ObjectMapper,
    restClientBuilder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val rest: RestClient = restClientBuilder
        .baseUrl(props.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .connectTimeout(props.connectTimeout)
                    // HTTP/1.1 explícito: o padrão do JDK tenta "upgrade" para HTTP/2 em
                    // texto puro (h2c), que servidores como Jetty cancelam no meio do pedido.
                    .version(HttpClient.Version.HTTP_1_1)
                    .build(),
            ).apply { setReadTimeout(props.readTimeout) },
        )
        .defaultHeaders { it.setBasicAuth(props.usuario, props.senha) }
        .build()

    /**
     * Envia a transferência com o EndToEndId como Idempotency-Key.
     * Chamar de novo com a mesma chave é seguro: a Ledger devolve o resultado gravado
     * em vez de executar outra vez. É assim que "consultamos" um resultado incerto,
     * já que a Ledger não oferece busca pela chave de idempotência.
     */
    fun transferir(idempotencyKey: String, pedido: TransferenciaLedger): ResultadoLedger =
        try {
            rest.post()
                .uri("/api/v1/transferencias")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(pedido)
                .exchange { _, resposta -> classificar(resposta.statusCode.value(), resposta.body.readAllBytes()) }
                ?: ResultadoLedger.Indeterminada("Ledger não devolveu resposta")
        } catch (e: ResourceAccessException) {
            // Inclui timeout de leitura: a Ledger pode ter recebido e processado o pedido.
            log.warn("Ledger sem resposta para idempotencyKey={}: {}", idempotencyKey, e.message)
            ResultadoLedger.Indeterminada("Ledger indisponível ou sem resposta no prazo")
        }

    /**
     * Pergunta à Ledger, COM A CREDENCIAL DO CLIENTE, se ele enxerga a conta.
     *
     * A Ledger (Operação Cofre) só mostra a um CLIENTE as contas de que ele é dono e
     * responde 404 para as alheias (anti-enumeração). Reaproveitamos essa regra em vez
     * de duplicar no Pix um cadastro de "quem é dono de qual conta".
     * O cabeçalho Authorization do cliente é repassado como veio e nunca é logado.
     */
    fun verificarAcesso(autorizacaoCliente: String, contaId: UUID): AcessoConta =
        try {
            rest.get()
                .uri("/api/v1/contas/{id}", contaId)
                .headers { it.set(HttpHeaders.AUTHORIZATION, autorizacaoCliente) } // substitui o Basic do OPERADOR
                .exchange { _, resposta ->
                    when (resposta.statusCode.value()) {
                        in 200..299 -> AcessoConta.PERMITIDO
                        401 -> AcessoConta.NAO_AUTENTICADO
                        403, 404 -> AcessoConta.NEGADO
                        else -> AcessoConta.INDISPONIVEL
                    }
                } ?: AcessoConta.INDISPONIVEL
        } catch (e: ResourceAccessException) {
            log.warn("Ledger sem resposta na verificação de acesso à conta {}: {}", contaId, e.message)
            AcessoConta.INDISPONIVEL
        }

    private fun classificar(status: Int, corpo: ByteArray): ResultadoLedger {
        val no = runCatching { json.readTree(corpo) }.getOrNull()
        val titulo = no?.path("title")?.asText().orEmpty()
        val detalhe = no?.path("detail")?.asText().orEmpty()
        return when {
            status in 200..299 -> {
                val id = no?.path("id")?.asText()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (id != null) {
                    ResultadoLedger.Efetivada(id)
                } else {
                    ResultadoLedger.Indeterminada("Ledger respondeu $status sem id de transação")
                }
            }
            // A Ledger não expõe "codigo" no problema; o título é o que distingue os 422.
            // Conflito de idempotência = mesma chave com outro corpo lá: não sabemos o que
            // foi executado sob essa chave, então nunca tratamos como recusa segura.
            status == 422 && titulo.startsWith("Conflito de idempotencia") ->
                ResultadoLedger.Indeterminada("Ledger acusou conflito de idempotência: $detalhe")
            status == 422 && detalhe.contains("Saldo insuficiente", ignoreCase = true) ->
                ResultadoLedger.Recusada("SALDO_INSUFICIENTE", "Saldo insuficiente na conta de origem")
            status == 422 -> ResultadoLedger.Recusada("LEDGER_RECUSOU", detalhe.ifBlank { "Ledger recusou a transferência" })
            status == 404 -> ResultadoLedger.Recusada("CONTA_NAO_ENCONTRADA", "Conta não encontrada na Ledger")
            status == 403 -> ResultadoLedger.Recusada("LEDGER_ACESSO_NEGADO", "Ledger negou acesso à conta")
            status == 400 -> ResultadoLedger.Recusada("LEDGER_DADOS_INVALIDOS", detalhe.ifBlank { "Ledger recusou os dados" })
            // 401 (credencial errada) e 5xx: nada garante que uma nova tentativa falhará.
            else -> {
                log.warn("Ledger respondeu {} '{}': {}", status, titulo, detalhe)
                ResultadoLedger.Indeterminada("Ledger respondeu HTTP $status")
            }
        }
    }
}
