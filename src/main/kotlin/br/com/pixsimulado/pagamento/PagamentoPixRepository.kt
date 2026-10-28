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

interface PagamentoPixRepository : JpaRepository<PagamentoPix, String> {

    /**
     * Reserva o EndToEndId. Devolve 1 se criou a linha e 0 se ela já existia.
     *
     * ON CONFLICT DO NOTHING é o coração da idempotência sob concorrência: se duas
     * transações tentam o mesmo E2E, a segunda fica bloqueada no índice da chave
     * primária até a primeira terminar, e então recebe 0 em vez de erro.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO pagamento_pix (end_to_end_id, hash_pedido, conta_origem_id, conta_destino_id,
                                       chave_destino, valor, descricao, estado, tentativas, criado_em, atualizado_em)
            VALUES (:e2e, :hash, :origem, :destino, :chave, :valor, :descricao, 'PENDENTE', 0, :agora, :agora)
            ON CONFLICT (end_to_end_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun reservar(
        @Param("e2e") endToEndId: String,
        @Param("hash") hashPedido: String,
        @Param("origem") contaOrigemId: UUID,
        @Param("destino") contaDestinoId: UUID,
        @Param("chave") chaveDestino: String,
        @Param("valor") valor: BigDecimal,
        @Param("descricao") descricao: String?,
        @Param("agora") agora: Instant,
    ): Int

    /**
     * Carrega com SELECT ... FOR UPDATE: enquanto uma transação decide o destino deste
     * pagamento (inclusive esperando a Ledger), nenhuma outra o reprocessa em paralelo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PagamentoPix p WHERE p.endToEndId = :e2e")
    fun travar(@Param("e2e") endToEndId: String): PagamentoPix?

    /** Incertos que já "descansaram" o intervalo e ainda têm tentativas, mais antigos primeiro. */
    @Query(
        value = """
            SELECT end_to_end_id FROM pagamento_pix
             WHERE estado = 'INCERTO' AND atualizado_em <= :limite AND tentativas < :maxTentativas
             ORDER BY atualizado_em
             LIMIT :lote
        """,
        nativeQuery = true,
    )
    fun incertosParaReconciliar(
        @Param("limite") limite: Instant,
        @Param("maxTentativas") maxTentativas: Int,
        @Param("lote") lote: Int,
    ): List<String>
}
