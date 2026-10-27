package br.com.pixsimulado.chave

import br.com.pixsimulado.config.PixProperties
import br.com.pixsimulado.shared.domain.ConflitoException
import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import br.com.pixsimulado.shared.domain.RecursoNaoEncontradoException
import br.com.pixsimulado.shared.domain.RegraNegocioException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Dados de entrada do cadastro, já desacoplados do formato HTTP. */
data class NovaChave(
    val tipo: TipoChave,
    val valor: String?,
    val contaId: String,
    val titularNome: String,
    val titularCpf: String,
)

/**
 * Regras do cadastro de chaves (inspiradas no regulamento do DICT):
 *  1. O valor deve ser válido para o tipo e é armazenado normalizado.
 *  2. Chave aleatória é gerada pelo servidor; o cliente não pode escolhê-la.
 *  3. Chave CPF deve ser o próprio CPF do titular.
 *  4. Uma conta pertence a um único titular.
 *  5. Limite de chaves ativas por conta (5 para pessoa física, configurável).
 *  6. Uma chave ativa pertence a uma única conta (unicidade global).
 */
@Service
class ChavePixService(
    private val repository: ChavePixRepository,
    private val pixProperties: PixProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun cadastrar(comando: NovaChave): ChavePix {
        val titularCpf = Cpf.normalizar(comando.titularCpf)
            ?: throw EntradaInvalidaException("CPF_TITULAR_INVALIDO", "CPF do titular inválido")

        val valor = resolverValor(comando)

        // Regra 3
        if (comando.tipo == TipoChave.CPF && valor != titularCpf) {
            throw RegraNegocioException(
                "CPF_DIVERGENTE_DO_TITULAR",
                "A chave CPF deve ser o CPF do próprio titular da conta",
            )
        }

        // A partir daqui lemos o estado da conta para decidir: serializamos por conta.
        repository.travarConta(comando.contaId)
        val ativasDaConta = repository.findByContaIdAndStatusOrderByCriadaEmAsc(comando.contaId, StatusChave.ATIVA)

        // Regra 4
        if (ativasDaConta.any { it.titularCpf != titularCpf }) {
            throw RegraNegocioException("CONTA_DE_OUTRO_TITULAR", "A conta informada pertence a outro titular")
        }

        // Regra 5
        val limite = pixProperties.chaves.limitePorConta
        if (ativasDaConta.size >= limite) {
            throw RegraNegocioException(
                "LIMITE_CHAVES_EXCEDIDO",
                "A conta já possui o limite de $limite chaves ativas",
            )
        }

        // Regra 6 - verificação antecipada para uma mensagem amigável...
        if (repository.existsByValorAndStatus(valor, StatusChave.ATIVA)) throw chaveJaCadastrada()

        val chave = ChavePix(
            id = UUID.randomUUID(),
            tipo = comando.tipo,
            valor = valor,
            contaId = comando.contaId,
            titularNome = comando.titularNome.trim(),
            titularCpf = titularCpf,
            criadaEm = clock.instant(),
        )

        return try {
            // ...e o índice único parcial do banco como garantia final contra corrida
            // entre contas diferentes (o advisory lock só protege a mesma conta).
            repository.saveAndFlush(chave).also {
                log.info("Chave Pix cadastrada id={} tipo={} conta={}", it.getId(), it.tipo, it.contaId)
            }
        } catch (e: DataIntegrityViolationException) {
            throw chaveJaCadastrada()
        }
    }

    @Transactional(readOnly = true)
    fun consultar(chaveBruta: String): ChavePix {
        val (_, valor) = TipoChave.identificar(chaveBruta)
        return repository.findByValorAndStatus(valor, StatusChave.ATIVA) ?: throw chaveNaoEncontrada()
    }

    @Transactional(readOnly = true)
    fun listarPorConta(contaId: String): List<ChavePix> =
        repository.findByContaIdAndStatusOrderByCriadaEmAsc(contaId, StatusChave.ATIVA)

    /** Exclusão lógica. Excluir chave inexistente ou já excluída resulta em 404. */
    @Transactional
    fun excluir(chaveBruta: String) {
        val chave = consultar(chaveBruta)
        chave.excluir(clock.instant()) // gravado no commit por dirty checking
        log.info("Chave Pix excluída id={} conta={}", chave.getId(), chave.contaId)
    }

    /** Regras 1 e 2. */
    private fun resolverValor(comando: NovaChave): String =
        if (comando.tipo == TipoChave.ALEATORIA) {
            if (!comando.valor.isNullOrBlank()) {
                throw RegraNegocioException(
                    "VALOR_NAO_PERMITIDO",
                    "Chave aleatória é gerada pelo sistema; não informe o campo 'valor'",
                )
            }
            UUID.randomUUID().toString() // UUID v4 em minúsculas
        } else {
            val valor = comando.valor
            if (valor.isNullOrBlank()) {
                throw EntradaInvalidaException("CHAVE_INVALIDA", "O campo 'valor' é obrigatório para chaves ${comando.tipo}")
            }
            comando.tipo.normalizar(valor)
        }

    private fun chaveJaCadastrada() =
        ConflitoException("CHAVE_JA_CADASTRADA", "Chave já cadastrada")

    private fun chaveNaoEncontrada() =
        RecursoNaoEncontradoException("CHAVE_NAO_ENCONTRADA", "Chave Pix não encontrada")
}
