package br.com.pixsimulado.pagamento

import br.com.pixsimulado.config.LedgerProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Liga o agendador só quando a reconciliação está habilitada. Nos testes ela fica
 * desligada e [ReconciliacaoPagamentos.executar] é chamado diretamente, sem esperar relógio.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "pix.ledger.reconciliacao", name = ["habilitada"], havingValue = "true", matchIfMissing = true)
class AgendamentoConfig

/**
 * Reconciliação de pagamentos e devoluções INCERTOS.
 *
 * Reenvia à Ledger, com a mesma Idempotency-Key, cada operação cujo resultado ficou
 * desconhecido (timeout, 5xx). Pela idempotência da Ledger, o reenvio devolve o que já
 * foi gravado ou executa pela primeira vez: nunca debita duas vezes.
 */
@Component
class ReconciliacaoPagamentos(
    private val repository: PagamentoPixRepository,
    private val service: PagamentoPixService,
    private val devolucoes: DevolucaoPixRepository,
    private val devolucaoService: DevolucaoPixService,
    private val props: LedgerProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return quantas operações (pagamentos + devoluções) foram reprocessadas nesta rodada. */
    @Scheduled(fixedDelayString = "\${pix.ledger.reconciliacao.intervalo:30s}")
    fun executar(): Int {
        val config = props.reconciliacao
        val limite = clock.instant().minus(config.intervalo)
        val pagamentos = repository.incertosParaReconciliar(limite, config.maxTentativas, config.lote)
        val devolvidas = devolucoes.incertasParaReconciliar(limite, config.maxTentativas, config.lote)

        // Uma operação problemática não pode travar a fila inteira.
        pagamentos.forEach { e2e ->
            runCatching { service.reconciliar(e2e) }
                .onFailure { log.error("Falha ao reconciliar pagamento e2e={}", e2e, it) }
        }
        devolvidas.forEach { id ->
            runCatching { devolucaoService.reconciliar(id) }
                .onFailure { log.error("Falha ao reconciliar devolução id={}", id, it) }
        }
        val total = pagamentos.size + devolvidas.size
        if (total > 0) log.info("Reconciliação reprocessou {} pagamento(s) e {} devolução(ões)", pagamentos.size, devolvidas.size)
        return total
    }
}
