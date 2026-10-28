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

/** Motivos de devolução (subconjunto dos códigos ISO 20022 usados no Pix). */
enum class MotivoDevolucao(val descricao: String) {
    MD06("Solicitada pelo cliente"),
    BE08("Erro operacional"),
    FR01("Fraude"),
}

/**
 * Devolução de um pagamento Pix: transferência inversa (recebedor -> pagador) na Ledger.
 * Criada por INSERT nativo com ON CONFLICT, como o pagamento ([DevolucaoPixRepository.reservar]).
 */
@Entity
@Table(name = "devolucao_pix")
class DevolucaoPix(
    @Id
    @Column(name = "id_devolucao")
    val idDevolucao: String,

    @Column(name = "end_to_end_id_original", nullable = false, updatable = false)
    val endToEndIdOriginal: String,

    @Column(name = "hash_pedido", nullable = false, updatable = false)
    val hashPedido: String,

    contaOrigemId: UUID,
    contaDestinoId: UUID,
    valor: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "motivo_devolucao", nullable = false, updatable = false)
    val motivo: MotivoDevolucao,

    descricao: String?,
    criadoEm: Instant,
) : OperacaoLedger(contaOrigemId, contaDestinoId, valor, descricao, criadoEm) {

    override val idempotencyKey: String get() = idDevolucao
}
