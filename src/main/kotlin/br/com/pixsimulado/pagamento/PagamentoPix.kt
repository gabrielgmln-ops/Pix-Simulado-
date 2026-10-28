package br.com.pixsimulado.pagamento

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Estados de um pagamento. [final] = não muda mais; repetir a requisição só relê.
 * INCERTO não é final: a reconciliação ainda vai descobrir se a Ledger debitou.
 */
enum class EstadoPagamento(val final: Boolean) {
    PENDENTE(false),
    LIQUIDADO(true),
    REJEITADO(true),
    INCERTO(false),
}

/**
 * Pagamento Pix identificado pelo EndToEndId.
 *
 * A linha é criada por INSERT nativo com ON CONFLICT (ver [PagamentoPixRepository.reservar]),
 * por isso não precisa de [org.springframework.data.domain.Persistable]: a entidade só
 * é carregada e atualizada por dirty checking. Os dados do pedido são imutáveis
 * (updatable = false); só o desfecho evolui, pelas transições abaixo.
 */
@Entity
@Table(name = "pagamento_pix")
class PagamentoPix(
    @Id
    @Column(name = "end_to_end_id")
    val endToEndId: String,

    @Column(name = "hash_pedido", nullable = false, updatable = false)
    val hashPedido: String,

    @Column(name = "conta_origem_id", nullable = false, updatable = false)
    val contaOrigemId: UUID,

    @Column(name = "conta_destino_id", nullable = false, updatable = false)
    val contaDestinoId: UUID,

    @Column(name = "chave_destino", nullable = false, updatable = false)
    val chaveDestino: String,

    @Column(nullable = false, updatable = false)
    val valor: BigDecimal,

    @Column(updatable = false)
    val descricao: String?,

    @Column(name = "criado_em", nullable = false, updatable = false)
    val criadoEm: Instant,
) {
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

    @Column(name = "atualizado_em", nullable = false)
    var atualizadoEm: Instant = criadoEm
        protected set

    /** Registra que uma chamada à Ledger vai ser feita (conta inclusive as repetições). */
    fun registrarTentativa(agora: Instant) {
        check(!estado.final) { "Pagamento $endToEndId já está ${estado.name}" }
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
        check(!estado.final) { "Pagamento $endToEndId já está ${estado.name}" }
        // Ao sair de INCERTO o motivo provisório deixa de valer.
        motivoCodigo = null
        motivoMensagem = null
        efeito()
        estado = novo
        atualizadoEm = agora
    }
}
