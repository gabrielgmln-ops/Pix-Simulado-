package br.com.pixsimulado.pagamento

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Pagamento Pix identificado pelo EndToEndId.
 *
 * A linha é criada por INSERT nativo com ON CONFLICT (ver [PagamentoPixRepository.reservar]),
 * por isso não precisa de [org.springframework.data.domain.Persistable]: a entidade só
 * é carregada e atualizada por dirty checking. Estados e transições vêm de [OperacaoLedger].
 *
 * Depois de LIQUIDADO, esta linha nunca mais muda: devoluções são registros próprios
 * ([DevolucaoPix]) e o valor devolvido é sempre calculado pela soma delas.
 */
@Entity
@Table(name = "pagamento_pix")
class PagamentoPix(
    @Id
    @Column(name = "end_to_end_id")
    val endToEndId: String,

    @Column(name = "hash_pedido", nullable = false, updatable = false)
    val hashPedido: String,

    contaOrigemId: UUID,
    contaDestinoId: UUID,

    @Column(name = "chave_destino", nullable = false, updatable = false)
    val chaveDestino: String,

    valor: BigDecimal,
    descricao: String?,
    criadoEm: Instant,
) : OperacaoLedger(contaOrigemId, contaDestinoId, valor, descricao, criadoEm) {

    override val idempotencyKey: String get() = endToEndId
}
