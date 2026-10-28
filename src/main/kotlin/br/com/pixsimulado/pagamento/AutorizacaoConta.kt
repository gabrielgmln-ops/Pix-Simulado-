package br.com.pixsimulado.pagamento

import br.com.pixsimulado.ledger.AcessoConta
import br.com.pixsimulado.ledger.LedgerClient
import br.com.pixsimulado.shared.domain.AcessoNegadoException
import br.com.pixsimulado.shared.domain.NaoAutenticadoException
import br.com.pixsimulado.shared.domain.ServicoIndisponivelException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Controle de acesso dos pagamentos (anti-IDOR).
 *
 * O Pix movimenta dinheiro na Ledger como OPERADOR, que enxerga todas as contas. Por
 * isso, sem esta checagem, qualquer um poderia informar a conta de outra pessoa como
 * origem. Antes de qualquer efeito, o Pix confirma com a Ledger, usando a credencial
 * do próprio cliente (HTTP Basic), que ele é dono da conta.
 *
 * Roda FORA da transação do pagamento: nenhum lock nem conexão do banco fica preso
 * enquanto esperamos a Ledger, e uma recusa aqui não deixa nenhum rastro gravado.
 */
@Component
class AutorizacaoConta(private val ledger: LedgerClient) {

    /** Exige que o cliente seja dono de [contaId]; senão 401/403/503. */
    fun exigirDono(autorizacao: String?, contaId: UUID) {
        when (consultar(autorizacao, contaId)) {
            AcessoConta.PERMITIDO -> Unit
            AcessoConta.NAO_AUTENTICADO -> throw naoAutenticado()
            AcessoConta.NEGADO -> throw AcessoNegadoException(
                "ACESSO_NEGADO",
                "A conta de origem não pertence ao usuário autenticado",
            )
            AcessoConta.INDISPONIVEL -> throw indisponivel()
        }
    }

    /**
     * Verdadeiro se o cliente é dono de alguma das contas (pagador ou recebedor).
     * Credencial recusada e Ledger fora do ar continuam sendo erros, não "não".
     */
    fun eDonoDeAlguma(autorizacao: String?, vararg contas: UUID): Boolean =
        contas.any { conta ->
            when (consultar(autorizacao, conta)) {
                AcessoConta.PERMITIDO -> true
                AcessoConta.NEGADO -> false
                AcessoConta.NAO_AUTENTICADO -> throw naoAutenticado()
                AcessoConta.INDISPONIVEL -> throw indisponivel()
            }
        }

    /**
     * Recusa logo pedidos sem credencial, antes de qualquer consulta: assim "pagamento
     * inexistente" (404) nunca é respondido a quem nem se identificou.
     */
    fun exigirCredencial(autorizacao: String?): String {
        // Só HTTP Basic: é o esquema que a Ledger aceita. O valor segue intacto para ela.
        if (autorizacao == null || !autorizacao.startsWith("Basic ", ignoreCase = true) || autorizacao.length <= 6) {
            throw naoAutenticado()
        }
        return autorizacao
    }

    private fun consultar(autorizacao: String?, contaId: UUID): AcessoConta =
        ledger.verificarAcesso(exigirCredencial(autorizacao), contaId)

    private fun naoAutenticado() =
        NaoAutenticadoException("NAO_AUTENTICADO", "Informe as credenciais do titular (HTTP Basic)")

    private fun indisponivel() =
        ServicoIndisponivelException("LEDGER_INDISPONIVEL", "Ledger indisponível; nada foi debitado, tente de novo")
}
