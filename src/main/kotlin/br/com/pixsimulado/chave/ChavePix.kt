package br.com.pixsimulado.chave

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

enum class StatusChave { ATIVA, EXCLUIDA }

/**
 * Chave Pix vinculada a uma conta da Ledger.
 *
 * A exclusão é lógica (status EXCLUIDA + data): transferências passadas continuam
 * apontando para um registro existente, e o mesmo valor pode ser cadastrado de novo,
 * pois a unicidade no banco vale apenas para chaves ATIVAS (índice único parcial).
 *
 * Implementa [Persistable] porque o id é gerado pela aplicação: sem isso, o Spring
 * Data trataria o objeto como existente e faria um SELECT antes de cada INSERT.
 */
@Entity
@Table(name = "chave_pix")
class ChavePix(
    @Id
    private val id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val tipo: TipoChave,

    /** Valor canônico (ver [TipoChave.normalizar]). */
    @Column(nullable = false, updatable = false)
    val valor: String,

    /** Identificador da conta na Ledger API que recebe os créditos desta chave. */
    @Column(name = "conta_id", nullable = false, updatable = false)
    val contaId: UUID,

    @Column(name = "titular_nome", nullable = false, updatable = false)
    val titularNome: String,

    /** CPF do titular, somente dígitos. */
    @Column(name = "titular_cpf", nullable = false, updatable = false)
    val titularCpf: String,

    @Column(name = "criada_em", nullable = false, updatable = false)
    val criadaEm: Instant,
) : Persistable<UUID> {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: StatusChave = StatusChave.ATIVA
        protected set // "protected": entidades JPA são "open" (plugin all-open)

    @Column(name = "excluida_em")
    var excluidaEm: Instant? = null
        protected set // "protected": entidades JPA são "open" (plugin all-open)

    /** Marca se o objeto ainda não foi gravado (controla INSERT vs UPDATE). */
    @Transient
    private var nova: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = nova

    @PostPersist
    @PostLoad
    private fun marcarComoPersistida() {
        nova = false
    }

    /** Exclusão lógica; idempotência fica a cargo do serviço (só exclui chaves ATIVAS). */
    fun excluir(agora: Instant) {
        check(status == StatusChave.ATIVA) { "Chave já excluída" }
        status = StatusChave.EXCLUIDA
        excluidaEm = agora
    }
}
