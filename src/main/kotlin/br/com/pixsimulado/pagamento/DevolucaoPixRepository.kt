package br.com.pixsimulado.pagamento

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface DevolucaoPixRepository : JpaRepository<DevolucaoPix, String> {

    /** Reserva o identificador da devolução: 1 se criou, 0 se já existia (ver V5). */
    @Modifying
    @Query(
        value = """
            INSERT INTO devolucao_pix (id_devolucao, end_to_end_id_original, hash_pedido, conta_origem_id,
                                       conta_destino_id, valor, motivo_devolucao, descricao, estado,
                                       tentativas, criado_em, atualizado_em)
            VALUES (:id, :original, :hash, :origem, :destino, :valor, :motivo, :descricao, 'PENDENTE', 0, :agora, :agora)
            ON CONFLICT (id_devolucao) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun reservar(
        @Param("id") idDevolucao: String,
        @Param("original") endToEndIdOriginal: String,
        @Param("hash") hashPedido: String,
        @Param("origem") contaOrigemId: UUID,
        @Param("destino") contaDestinoId: UUID,
        @Param("valor") valor: BigDecimal,
        @Param("motivo") motivo: String,
        @Param("descricao") descricao: String?,
        @Param("agora") agora: Instant,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DevolucaoPix d WHERE d.idDevolucao = :id")
    fun travar(@Param("id") idDevolucao: String): DevolucaoPix?

    fun findByEndToEndIdOriginalOrderByCriadoEmAsc(endToEndIdOriginal: String): List<DevolucaoPix>

    /**
     * Quanto do pagamento já está COMPROMETIDO com devoluções: tudo que não foi recusado.
     * INCERTO entra de propósito: se não sabemos se a Ledger devolveu, assumimos que sim,
     * para nunca devolver mais do que foi pago.
     */
    @Query(
        value = """
            SELECT COALESCE(SUM(valor), 0) FROM devolucao_pix
             WHERE end_to_end_id_original = :original AND estado <> 'REJEITADO'
        """,
        nativeQuery = true,
    )
    fun valorComprometido(@Param("original") endToEndIdOriginal: String): BigDecimal

    /** Quanto já voltou de fato ao pagador (somente devoluções liquidadas). */
    @Query(
        value = """
            SELECT COALESCE(SUM(valor), 0) FROM devolucao_pix
             WHERE end_to_end_id_original = :original AND estado = 'LIQUIDADO'
        """,
        nativeQuery = true,
    )
    fun valorDevolvido(@Param("original") endToEndIdOriginal: String): BigDecimal

    @Query(
        value = """
            SELECT id_devolucao FROM devolucao_pix
             WHERE estado = 'INCERTO' AND atualizado_em <= :limite AND tentativas < :maxTentativas
             ORDER BY atualizado_em
             LIMIT :lote
        """,
        nativeQuery = true,
    )
    fun incertasParaReconciliar(
        @Param("limite") limite: Instant,
        @Param("maxTentativas") maxTentativas: Int,
        @Param("lote") lote: Int,
    ): List<String>
}
