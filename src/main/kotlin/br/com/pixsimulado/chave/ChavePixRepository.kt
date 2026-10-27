package br.com.pixsimulado.chave

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ChavePixRepository : JpaRepository<ChavePix, UUID> {

    fun findByValorAndStatus(valor: String, status: StatusChave): ChavePix?

    fun existsByValorAndStatus(valor: String, status: StatusChave): Boolean

    fun findByContaIdAndStatusOrderByCriadaEmAsc(contaId: String, status: StatusChave): List<ChavePix>

    /**
     * Serializa cadastros concorrentes na MESMA conta até o fim da transação.
     *
     * Sem isso, duas requisições simultâneas poderiam ler "4 chaves" e ambas gravar,
     * furando o limite de 5. O advisory lock do PostgreSQL é liberado automaticamente
     * no commit/rollback e não bloqueia outras contas.
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:contaId))) AS trava",
        nativeQuery = true,
    )
    fun travarConta(@Param("contaId") contaId: String): Int
}
