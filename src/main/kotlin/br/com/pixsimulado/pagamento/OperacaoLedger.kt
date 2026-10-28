package br.com.pixsimulado.pagamento

import br.com.pixsimulado.ledger.LedgerClient
import br.com.pixsimulado.ledger.ResultadoLedger
import br.com.pixsimulado.ledger.TransferenciaLedger
import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.MappedSuperclass
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Estados de uma operação na Ledger (pagamento ou devolução).
 * [final] = não muda mais; repetir a requisição só relê.
 * INCERTO não é final: a reconciliação ainda vai descobrir se a Ledger debitou.
 */
enum class EstadoPagamento(val final: Boolean) {
    PENDENTE(false),
    LIQUIDADO(true),
    REJEITADO(true),
    INCERTO(false),
}

/**
 * Parte comum a pagamentos e devoluções: ambos são uma transferência na Ledger,
 * identificada por um id idempotente, com o mesmo ciclo de vida e as mesmas colunas
 * de desfecho. Os dados do pedido ficam nas subclasses e são imutáveis.
 */
@MappedSuperclass
abstract class OperacaoLedger(
    @Column(name = "conta_origem_id", nullable = false, updatable = false)
    val contaOrigemId: UUID,

    @Column(name = "conta_destino_id", nullable = false, updatable = false)
    val contaDestinoId: UUID,

    @Column(nullable = false, updatable = false)
    val valor: BigDecimal,

    @Column(updatable = false)
    val descricao: String?,

    @Column(name = "criado_em", nullable = false, updatable = false)
    val criadoEm: Instant,
) {
    /** Id da operação, enviado à Ledger como Idempotency-Key. */
    abstract val idempotencyKey: String

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var estado: EstadoPagamento = EstadoPagamento.PENDENTE
        protected set // "protected": entidades JPA são "open" (plugin all-open)

    @Column(name = "ledger_transacao_id")
    var ledgerTransacaoId: UUID? = null
        protected set

    @Column(name = "motivo_codigo")
    var motivoCodigo: String? = null
        protected set

    @Column(name = "motivo_mensagem")
    var motivoMensagem: String? = null
        protected set

    @Column(nullable = false)
    var tentativas: Int = 0
        protected set

    /** Para operações finais, é o instante do desfecho (ex.: a liquidação). */
    @Column(name = "atualizado_em", nullable = false)
    var atualizadoEm: Instant = criadoEm
        protected set

    /** Registra que uma chamada à Ledger vai ser feita (conta inclusive as repetições). */
    fun registrarTentativa(agora: Instant) {
        check(!estado.final) { "Operação $idempotencyKey já está ${estado.name}" }
        tentativas++
        atualizadoEm = agora
    }

    fun liquidar(transacaoId: UUID, agora: Instant) = concluir(EstadoPagamento.LIQUIDADO, agora) {
        ledgerTransacaoId = transacaoId
    }

    fun rejeitar(codigo: String, mensagem: String, agora: Instant) = concluir(EstadoPagamento.REJEITADO, agora) {
        motivoCodigo = codigo
        motivoMensagem = mensagem.take(255)
    }

    /** Resultado desconhecido: guarda o motivo e espera a reconciliação. */
    fun marcarIncerto(motivo: String, agora: Instant) = concluir(EstadoPagamento.INCERTO, agora) {
        motivoCodigo = "RESULTADO_INCERTO"
        motivoMensagem = motivo.take(255)
    }

    private inline fun concluir(novo: EstadoPagamento, agora: Instant, efeito: () -> Unit) {
        check(!estado.final) { "Operação $idempotencyKey já está ${estado.name}" }
        // Ao sair de INCERTO o motivo provisório deixa de valer.
        motivoCodigo = null
        motivoMensagem = null
        efeito()
        estado = novo
        atualizadoEm = agora
    }
}

/**
 * Envia uma operação à Ledger e aplica o desfecho no estado dela.
 * Compartilhado por pagamentos e devoluções para que a regra "timeout é INCERTO,
 * nunca falha" exista em um único lugar.
 */
@Component
class ExecutorLedger(private val ledger: LedgerClient, private val clock: Clock) {

    fun enviar(operacao: OperacaoLedger) {
        operacao.registrarTentativa(clock.instant())
        val pedido = TransferenciaLedger(
            contaOrigemId = operacao.contaOrigemId,
            contaDestinoId = operacao.contaDestinoId,
            valor = operacao.valor,
            descricao = operacao.descricao,
        )
        when (val resultado = ledger.transferir(operacao.idempotencyKey, pedido)) {
            is ResultadoLedger.Efetivada -> operacao.liquidar(resultado.transacaoId, clock.instant())
            is ResultadoLedger.Recusada -> operacao.rejeitar(resultado.codigo, resultado.mensagem, clock.instant())
            is ResultadoLedger.Indeterminada -> operacao.marcarIncerto(resultado.motivo, clock.instant())
        }
    }
}
