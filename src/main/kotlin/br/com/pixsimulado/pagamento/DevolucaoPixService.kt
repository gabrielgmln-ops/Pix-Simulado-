package br.com.pixsimulado.pagamento

import br.com.pixsimulado.config.LedgerProperties
import br.com.pixsimulado.config.PixProperties
import br.com.pixsimulado.shared.domain.RecursoNaoEncontradoException
import br.com.pixsimulado.shared.domain.RegraNegocioException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat

/** Pedido de devolução já desacoplado do HTTP. */
data class NovaDevolucao(
    val idDevolucao: String,
    val valor: BigDecimal,
    val motivo: MotivoDevolucao,
    val descricao: String?,
)

/** @property repetida true quando veio de uma devolução já existente com o mesmo id. */
data class ResultadoDevolucao(val devolucao: DevolucaoPix, val repetida: Boolean)

/**
 * Devoluções de pagamentos Pix (lançamento inverso na Ledger).
 *
 * Regras:
 *  1. Só se devolve pagamento LIQUIDADO, dentro do prazo (90 dias por padrão) contado
 *     da liquidação.
 *  2. Pode ser parcial e repetida, mas a soma comprometida (tudo que não foi recusado,
 *     inclusive INCERTO) nunca passa do valor pago.
 *  3. O pagamento original nunca é alterado: a devolução é um registro novo e uma
 *     transferência nova na Ledger, recebedor -> pagador.
 *  4. Idempotência pelo id da devolução (prefixo D), igual ao pagamento: mesmo pedido
 *     devolve o mesmo resultado; mesmo id com dados diferentes = 422.
 *
 * Concorrência: o pagamento original é travado (SELECT ... FOR UPDATE) antes da soma.
 * Duas devoluções simultâneas do mesmo pagamento fazem fila nesse lock, então a segunda
 * já enxerga a primeira na soma e não consegue furar o limite.
 */
@Service
class DevolucaoPixService(
    private val repository: DevolucaoPixRepository,
    private val pagamentos: PagamentoPixRepository,
    private val executor: ExecutorLedger,
    private val pixProperties: PixProperties,
    private val ledgerProperties: LedgerProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Gera o identificador de uma nova devolução (prefixo D). */
    fun novoId(): EndToEndId = EndToEndId.gerar(pixProperties.ispb, clock, EndToEndId.DEVOLUCAO)

    @Transactional
    fun devolver(endToEndIdOriginal: String, comando: NovaDevolucao): ResultadoDevolucao {
        val original = EndToEndId.de(endToEndIdOriginal).valor
        val id = EndToEndId.de(comando.idDevolucao, EndToEndId.DEVOLUCAO)
        if (id.ispb != pixProperties.ispb) {
            throw RegraNegocioException(
                "ID_DEVOLUCAO_DE_OUTRA_INSTITUICAO",
                "O identificador da devolução deve ser gerado por esta instituição (ISPB ${pixProperties.ispb})",
            )
        }
        val hash = hashDoPedido(original, comando)

        // Repetição: a devolução já existe.
        repository.travar(id.valor)?.let { return repetir(it, original, hash) }

        // Ordem de locks sempre pagamento -> devolução, nos dois caminhos: sem deadlock.
        val pagamento = pagamentos.travar(original)
            ?: throw RecursoNaoEncontradoException("PAGAMENTO_NAO_ENCONTRADO", "Pagamento não encontrado")

        // Outra requisição com o mesmo id pode ter sido concluída enquanto esperávamos o lock.
        repository.travar(id.valor)?.let { return repetir(it, original, hash) }

        validarIdentificadorNovo(id)
        validarDevolvivel(pagamento, comando.valor)

        repository.reservar(
            idDevolucao = id.valor,
            endToEndIdOriginal = original,
            hashPedido = hash,
            // Sentido inverso: quem recebeu devolve a quem pagou.
            contaOrigemId = pagamento.contaDestinoId,
            contaDestinoId = pagamento.contaOrigemId,
            valor = comando.valor,
            motivo = comando.motivo.name,
            descricao = comando.descricao ?: "Devolucao ${comando.motivo.name} do Pix $original",
            agora = clock.instant(),
        )
        val devolucao = repository.travar(id.valor)!! // acabamos de inserir, sob o lock do pagamento

        enviar(devolucao)
        return ResultadoDevolucao(devolucao, repetida = false)
    }

    @Transactional(readOnly = true)
    fun listar(endToEndIdOriginal: String): List<DevolucaoPix> =
        repository.findByEndToEndIdOriginalOrderByCriadoEmAsc(EndToEndId.de(endToEndIdOriginal).valor)

    @Transactional(readOnly = true)
    fun valorDevolvido(endToEndIdOriginal: String): BigDecimal = repository.valorDevolvido(endToEndIdOriginal)

    /** Reenvio de uma devolução INCERTA pela reconciliação. */
    @Transactional
    fun reconciliar(idDevolucao: String) {
        val devolucao = repository.travar(idDevolucao) ?: return
        if (devolucao.estado != EstadoPagamento.INCERTO) return
        if (devolucao.tentativas >= ledgerProperties.reconciliacao.maxTentativas) return
        enviar(devolucao)
    }

    private fun repetir(devolucao: DevolucaoPix, original: String, hash: String): ResultadoDevolucao {
        if (devolucao.endToEndIdOriginal != original || devolucao.hashPedido != hash) {
            throw RegraNegocioException(
                "ID_DEVOLUCAO_JA_UTILIZADO",
                "Identificador de devolução já utilizado em um pedido com dados diferentes",
            )
        }
        if (devolucao.estado == EstadoPagamento.INCERTO &&
            devolucao.tentativas < ledgerProperties.reconciliacao.maxTentativas
        ) {
            enviar(devolucao) // mesma Idempotency-Key: a Ledger nunca devolve duas vezes
        }
        return ResultadoDevolucao(devolucao, repetida = true)
    }

    /** Regras 1 e 2. */
    private fun validarDevolvivel(pagamento: PagamentoPix, valor: BigDecimal) {
        if (pagamento.estado != EstadoPagamento.LIQUIDADO) {
            throw RegraNegocioException(
                "PAGAMENTO_NAO_LIQUIDADO",
                "Só é possível devolver um pagamento liquidado (estado atual: ${pagamento.estado.name})",
            )
        }
        // LIQUIDADO é final, então atualizadoEm é exatamente o instante da liquidação.
        val prazoFinal = pagamento.atualizadoEm.plus(pixProperties.devolucao.prazo)
        if (clock.instant().isAfter(prazoFinal)) {
            throw RegraNegocioException(
                "PRAZO_DEVOLUCAO_EXPIRADO",
                "O prazo de devolução deste pagamento terminou em $prazoFinal",
            )
        }
        val comprometido = repository.valorComprometido(pagamento.endToEndId)
        val disponivel = pagamento.valor.subtract(comprometido)
        if (valor > disponivel) {
            throw RegraNegocioException(
                "VALOR_DEVOLUCAO_EXCEDIDO",
                "Valor acima do disponível para devolução (disponível: ${disponivel.toPlainString()})",
            )
        }
    }

    private fun validarIdentificadorNovo(id: EndToEndId) {
        val agora = clock.instant()
        if (id.geradoEm.isAfter(agora.plus(PagamentoPixService.TOLERANCIA_RELOGIO)) ||
            id.geradoEm.isBefore(agora.minus(PagamentoPixService.VALIDADE_E2E))
        ) {
            throw RegraNegocioException("ID_DEVOLUCAO_EXPIRADO", "Identificador de devolução fora do prazo; gere um novo")
        }
    }

    private fun enviar(devolucao: DevolucaoPix) {
        executor.enviar(devolucao)
        log.info(
            "Devolução Pix id={} original={} estado={} tentativa={} valor={}",
            devolucao.idDevolucao, devolucao.endToEndIdOriginal, devolucao.estado, devolucao.tentativas, devolucao.valor,
        )
    }

    private fun hashDoPedido(original: String, comando: NovaDevolucao): String {
        val canonico = listOf(
            original,
            "valor=${comando.valor.stripTrailingZeros().toPlainString()}",
            "motivo=${comando.motivo.name}",
            "descricao=${comando.descricao.orEmpty()}",
        ).joinToString("\u0000")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonico.toByteArray(Charsets.UTF_8)))
    }
}
