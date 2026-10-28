package br.com.pixsimulado.pagamento

import br.com.pixsimulado.chave.ChavePixService
import br.com.pixsimulado.chave.TipoChave
import br.com.pixsimulado.config.LedgerProperties
import br.com.pixsimulado.config.PixProperties
import br.com.pixsimulado.qrcode.BrCode
import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import br.com.pixsimulado.shared.domain.RecursoNaoEncontradoException
import br.com.pixsimulado.shared.domain.RegraNegocioException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

/** Pedido de pagamento já desacoplado do HTTP. Exatamente um entre [chave] e [qrPayload]. */
data class NovoPagamento(
    val endToEndId: String,
    val contaOrigemId: UUID,
    val chave: String?,
    val qrPayload: String?,
    val valor: BigDecimal?,
    val descricao: String?,
)

/** @property repetido true quando a resposta veio de um pagamento já existente com o mesmo E2E. */
data class ResultadoPagamento(val pagamento: PagamentoPix, val repetido: Boolean)

/**
 * Pagamentos Pix sobre a Ledger, com idempotência por EndToEndId.
 *
 * Regras:
 *  1. O E2E tem de ser desta instituição (ISPB) e recente (até [VALIDADE_E2E]).
 *  2. Mesmo E2E + mesmo pedido = mesma resposta, sem nova movimentação.
 *     Mesmo E2E + pedido diferente = 422 END_TO_END_ID_JA_UTILIZADO.
 *  3. Destino por chave ou por QR Code; valor do QR, quando existe, prevalece.
 *  4. Origem e destino diferentes.
 *  5. Resultado incerto (timeout) nunca vira "falha": fica INCERTO e é reenviado com a
 *     MESMA Idempotency-Key, então a Ledger jamais debita duas vezes.
 *
 * Decisão consciente: a chamada HTTP à Ledger acontece DENTRO da transação que segura o
 * lock da linha do pagamento. Isso serializa requisições concorrentes do mesmo E2E (a
 * segunda espera e lê o desfecho, sem chamar a Ledger) ao custo de prender uma conexão
 * do pool por até o read-timeout. Para um Pix com timeout curto, é um bom negócio.
 */
@Service
class PagamentoPixService(
    private val repository: PagamentoPixRepository,
    private val chaveService: ChavePixService,
    private val executor: ExecutorLedger,
    private val pixProperties: PixProperties,
    private val ledgerProperties: LedgerProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Gera um E2E novo para o cliente usar no pagamento (e nas repetições dele). */
    fun novoEndToEndId(): EndToEndId = EndToEndId.gerar(pixProperties.ispb, clock)

    @Transactional
    fun pagar(comando: NovoPagamento): ResultadoPagamento {
        val e2e = EndToEndId.de(comando.endToEndId)
        if (e2e.ispb != pixProperties.ispb) {
            throw RegraNegocioException(
                "END_TO_END_ID_DE_OUTRA_INSTITUICAO",
                "O EndToEndId deve ser gerado por esta instituição (ISPB ${pixProperties.ispb})",
            )
        }
        val hash = hashDoPedido(comando)

        // Caminho da repetição: o pagamento já existe (e já está travado para nós).
        repository.travar(e2e.valor)?.let { return repetir(it, hash) }

        // Caminho do pagamento novo.
        validarPrazo(e2e)
        val destino = resolverDestino(comando)
        if (destino.contaId == comando.contaOrigemId) {
            throw RegraNegocioException("MESMA_CONTA", "Conta de origem e de destino devem ser diferentes")
        }

        val inserido = repository.reservar(
            endToEndId = e2e.valor,
            hashPedido = hash,
            contaOrigemId = comando.contaOrigemId,
            contaDestinoId = destino.contaId,
            chaveDestino = destino.chave,
            valor = destino.valor,
            descricao = destino.descricao,
            agora = clock.instant(),
        ) == 1
        val pagamento = repository.travar(e2e.valor)!! // existe: acabamos de inserir ou outro inseriu

        // Corrida: outra requisição com o mesmo E2E venceu o INSERT e já terminou.
        if (!inserido) return repetir(pagamento, hash)

        enviarParaLedger(pagamento)
        return ResultadoPagamento(pagamento, repetido = false)
    }

    @Transactional(readOnly = true)
    fun consultar(endToEndId: String): PagamentoPix =
        repository.findById(EndToEndId.de(endToEndId).valor).orElseThrow {
            RecursoNaoEncontradoException("PAGAMENTO_NAO_ENCONTRADO", "Pagamento não encontrado")
        }

    /**
     * Reenvio de um INCERTO pela reconciliação. Chamado um a um, cada qual na sua
     * transação, para que a falha de um não desfaça o progresso dos outros.
     */
    @Transactional
    fun reconciliar(endToEndId: String) {
        val pagamento = repository.travar(endToEndId) ?: return
        // Outro processo (ou o próprio cliente repetindo) pode ter resolvido antes de nós.
        if (pagamento.estado != EstadoPagamento.INCERTO) return
        if (pagamento.tentativas >= ledgerProperties.reconciliacao.maxTentativas) return
        enviarParaLedger(pagamento)
    }

    private fun repetir(pagamento: PagamentoPix, hash: String): ResultadoPagamento {
        if (pagamento.hashPedido != hash) {
            throw RegraNegocioException(
                "END_TO_END_ID_JA_UTILIZADO",
                "EndToEndId já utilizado em um pagamento com dados diferentes",
            )
        }
        // Incerto: a repetição do cliente também serve de consulta à Ledger (com a mesma
        // chave, a Ledger devolve o que gravou ou executa pela primeira vez, nunca duas).
        if (pagamento.estado == EstadoPagamento.INCERTO &&
            pagamento.tentativas < ledgerProperties.reconciliacao.maxTentativas
        ) {
            enviarParaLedger(pagamento)
        }
        return ResultadoPagamento(pagamento, repetido = true)
    }

    private fun enviarParaLedger(pagamento: PagamentoPix) {
        executor.enviar(pagamento)
        log.info(
            "Pagamento Pix e2e={} estado={} tentativa={} valor={}",
            pagamento.endToEndId, pagamento.estado, pagamento.tentativas, pagamento.valor,
        )
    }

    /** Regra 1, só para E2E ainda não usado: repetições antigas continuam respondendo. */
    private fun validarPrazo(e2e: EndToEndId) {
        val agora = clock.instant()
        val geradoEm = e2e.geradoEm
        if (geradoEm.isAfter(agora.plus(TOLERANCIA_RELOGIO)) || geradoEm.isBefore(agora.minus(VALIDADE_E2E))) {
            throw RegraNegocioException(
                "END_TO_END_ID_EXPIRADO",
                "EndToEndId fora do prazo de validade; gere um novo",
            )
        }
    }

    private data class Destino(val chave: String, val contaId: UUID, val valor: BigDecimal, val descricao: String?)

    /** Regra 3. */
    private fun resolverDestino(comando: NovoPagamento): Destino {
        val (chaveInformada, valorDoQr, descricaoDoQr) = when {
            comando.chave != null && comando.qrPayload != null -> throw destinoAmbiguo()
            comando.chave != null -> Triple(comando.chave, null, null)
            comando.qrPayload != null -> BrCode.ler(comando.qrPayload).let { Triple(it.chave, it.valor, it.descricao) }
            else -> throw destinoAmbiguo()
        }

        val valor = when {
            valorDoQr == null -> comando.valor
                ?: throw EntradaInvalidaException("VALOR_OBRIGATORIO", "Informe o valor: o destino não define um")
            comando.valor != null && comando.valor.compareTo(valorDoQr) != 0 -> throw RegraNegocioException(
                "VALOR_QR_DIVERGENTE",
                "O QR Code fixa o valor em $valorDoQr; não é possível pagar outro valor",
            )
            else -> valorDoQr
        }

        val chave = chaveService.consultar(chaveInformada) // 400 formato inválido, 404 inexistente
        return Destino(chave.valor, chave.contaId, valor, comando.descricao ?: descricaoDoQr)
    }

    /**
     * Impressão digital do pedido como o cliente o enviou, normalizada para que
     * diferenças irrelevantes (10 vs 10.00, e-mail em maiúsculas) não virem "outro pedido".
     * Não depende do banco: assim uma repetição é reconhecida antes de qualquer consulta
     * (até mesmo se a chave de destino tiver sido excluída depois do pagamento).
     */
    private fun hashDoPedido(comando: NovoPagamento): String {
        val chave = comando.chave?.let { runCatching { TipoChave.identificar(it).second }.getOrDefault(it.trim()) }
        val canonico = listOf(
            comando.contaOrigemId.toString(),
            "chave=${chave.orEmpty()}",
            "qr=${comando.qrPayload?.trim().orEmpty()}",
            "valor=${comando.valor?.stripTrailingZeros()?.toPlainString().orEmpty()}",
            "descricao=${comando.descricao.orEmpty()}",
        ).joinToString("\u0000") // separador que não aparece nos campos: evita ambiguidade
        val digest = MessageDigest.getInstance("SHA-256").digest(canonico.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private fun destinoAmbiguo() =
        EntradaInvalidaException("DESTINO_INVALIDO", "Informe exatamente um destino: 'chave' ou 'qrPayload'")

    companion object {
        /** Igual à janela de idempotência da Ledger: depois disso, repetir lá não é seguro. */
        val VALIDADE_E2E: Duration = Duration.ofHours(24)

        /** Folga para relógios levemente adiantados entre cliente e servidor. */
        val TOLERANCIA_RELOGIO: Duration = Duration.ofMinutes(5)
    }
}
