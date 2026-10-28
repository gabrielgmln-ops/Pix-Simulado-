package br.com.pixsimulado.pagamento

import br.com.pixsimulado.shared.domain.RegraNegocioException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * API REST de pagamentos Pix. O fluxo tem dois passos:
 *  1. POST /end-to-end-id: a instituição gera o identificador do pagamento;
 *  2. POST com esse identificador. Repetir o passo 2 (ex.: após um timeout no app)
 *     é seguro e devolve o mesmo resultado, com o cabeçalho Idempotent-Replayed: true.
 */
@RestController
@RequestMapping("/api/v1/pagamentos")
@Tag(name = "Pagamentos Pix", description = "Transferência via Ledger com idempotência por EndToEndId")
class PagamentoPixController(private val service: PagamentoPixService) {

    @PostMapping("/end-to-end-id")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Gera um EndToEndId para um novo pagamento")
    fun novoEndToEndId(): EndToEndIdResponse = EndToEndIdResponse(service.novoEndToEndId().valor)

    @PostMapping
    @Operation(summary = "Paga por chave ou por QR Code (idempotente por EndToEndId)")
    @ApiResponse(responseCode = "201", description = "Pagamento liquidado na Ledger")
    @ApiResponse(responseCode = "202", description = "Resultado ainda incerto (Ledger sem resposta); consulte depois")
    @ApiResponse(responseCode = "400", description = "Dados em formato inválido")
    @ApiResponse(responseCode = "404", description = "Chave de destino não encontrada")
    @ApiResponse(responseCode = "422", description = "Recusado (saldo, E2E reutilizado, valor do QR divergente...)")
    fun pagar(@Valid @RequestBody request: PagarRequest): ResponseEntity<PagamentoResponse> {
        val (pagamento, repetido) = service.pagar(request.paraComando())

        // A recusa já está gravada; só agora, fora da transação, ela vira erro HTTP.
        // Repetir um pagamento recusado devolve a mesma recusa.
        if (pagamento.estado == EstadoPagamento.REJEITADO) {
            throw RegraNegocioException(
                pagamento.motivoCodigo ?: "PAGAMENTO_REJEITADO",
                pagamento.motivoMensagem ?: "Pagamento recusado",
            )
        }

        val status = if (pagamento.estado == EstadoPagamento.LIQUIDADO) HttpStatus.CREATED else HttpStatus.ACCEPTED
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{e2e}")
            .buildAndExpand(pagamento.endToEndId)
            .toUri()
        return ResponseEntity.status(status)
            .location(location)
            .header(CABECALHO_REPETIDO, repetido.toString())
            .body(PagamentoResponse.de(pagamento))
    }

    @GetMapping("/{endToEndId}")
    @Operation(summary = "Consulta um pagamento pelo EndToEndId")
    @ApiResponse(responseCode = "200", description = "Pagamento encontrado (qualquer estado)")
    @ApiResponse(responseCode = "404", description = "Pagamento não encontrado")
    fun consultar(@PathVariable endToEndId: String): PagamentoResponse =
        PagamentoResponse.de(service.consultar(endToEndId))

    companion object {
        /** Mesmo nome usado pela Ledger: avisa que a resposta é de um pedido já processado. */
        const val CABECALHO_REPETIDO = "Idempotent-Replayed"
    }
}
