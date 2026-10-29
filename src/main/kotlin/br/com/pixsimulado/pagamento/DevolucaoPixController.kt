package br.com.pixsimulado.pagamento

import br.com.pixsimulado.config.OpenApiConfig
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import br.com.pixsimulado.shared.domain.RecursoNaoEncontradoException
import br.com.pixsimulado.shared.domain.RegraNegocioException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Schema(description = "Identificador gerado pela instituição para uma nova devolução")
data class IdDevolucaoResponse(
    @Schema(example = "D9999999920261028153012aBcDeFgHiJk")
    val idDevolucao: String,
)

@Schema(description = "Devolução total ou parcial de um pagamento liquidado")
data class DevolverRequest(
    @field:NotBlank
    @Schema(description = "Obtido em POST .../devolucoes/id; repita-o para repetir o pedido com segurança")
    val idDevolucao: String?,

    // @Digits recusa fração de centavo com 400: nunca arredondamos em silêncio.
    @field:NotNull
    @field:Positive
    @field:Digits(integer = 17, fraction = 2)
    @Schema(example = "10.00")
    val valor: BigDecimal?,

    @field:NotNull
    @Schema(description = "MD06 = pedido do cliente, BE08 = erro operacional, FR01 = fraude", example = "MD06")
    val motivo: MotivoDevolucao?,

    @field:Size(max = 140)
    val descricao: String?,
) {
    fun paraComando() = NovaDevolucao(
        idDevolucao = idDevolucao!!, // !! seguro: garantido pelo Bean Validation antes
        valor = valor!!,
        motivo = motivo!!,
        descricao = descricao?.trim()?.takeIf { it.isNotEmpty() },
    )
}

@Schema(description = "Devolução Pix")
data class DevolucaoResponse(
    val idDevolucao: String,
    val endToEndIdOriginal: String,
    val estado: EstadoPagamento,
    val valor: BigDecimal,
    val motivo: MotivoDevolucao,
    @Schema(description = "Conta do recebedor original, de onde o dinheiro sai")
    val contaOrigemId: UUID,
    @Schema(description = "Conta do pagador original, que recebe de volta")
    val contaDestinoId: UUID,
    val descricao: String?,
    val ledgerTransacaoId: UUID?,
    val falha: MotivoResponse?,
    val criadoEm: Instant,
    val atualizadoEm: Instant,
) {
    companion object {
        fun de(d: DevolucaoPix) = DevolucaoResponse(
            idDevolucao = d.idDevolucao,
            endToEndIdOriginal = d.endToEndIdOriginal,
            estado = d.estado,
            valor = d.valor,
            motivo = d.motivo,
            contaOrigemId = d.contaOrigemId,
            contaDestinoId = d.contaDestinoId,
            descricao = d.descricao,
            ledgerTransacaoId = d.ledgerTransacaoId,
            falha = d.motivoCodigo?.let { MotivoResponse(it, d.motivoMensagem.orEmpty()) },
            criadoEm = d.criadoEm,
            atualizadoEm = d.atualizadoEm,
        )
    }
}

/**
 * API de devoluções, aninhada no pagamento original.
 * Só o RECEBEDOR (dono da conta de destino do pagamento) devolve; pagador e recebedor
 * consultam. A titularidade é conferida na Ledger, como nos pagamentos.
 */
@RestController
@RequestMapping("/api/v1/pagamentos/{endToEndId}/devolucoes")
@Tag(name = "Devoluções Pix", description = "Devolução total ou parcial, sem alterar o pagamento original")
class DevolucaoPixController(
    private val service: DevolucaoPixService,
    private val pagamentos: PagamentoPixService,
    private val autorizacao: AutorizacaoConta,
) {

    @PostMapping("/id")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Gera o identificador de uma nova devolução (prefixo D)")
    fun novoId(@PathVariable endToEndId: String): IdDevolucaoResponse = IdDevolucaoResponse(service.novoId().valor)

    @PostMapping
    @SecurityRequirement(name = OpenApiConfig.BASIC)
    @Operation(summary = "Devolve um pagamento liquidado (idempotente pelo id da devolução)")
    @ApiResponse(responseCode = "201", description = "Devolução liquidada na Ledger",
        headers = [
            Header(name = "Location", description = "URL para consultar a operação"),
            Header(
                name = PagamentoPixController.CABECALHO_REPETIDO,
                description = "true quando a resposta é de um pedido já processado (repetição idempotente)",
                schema = io.swagger.v3.oas.annotations.media.Schema(type = "boolean"),
            ),
        ],
    )
    @ApiResponse(responseCode = "202", description = "Resultado ainda incerto; consulte depois",
        headers = [
            Header(name = "Location", description = "URL para consultar a operação"),
            Header(
                name = PagamentoPixController.CABECALHO_REPETIDO,
                description = "true quando a resposta é de um pedido já processado (repetição idempotente)",
                schema = io.swagger.v3.oas.annotations.media.Schema(type = "boolean"),
            ),
        ],
    )
    @ApiResponse(responseCode = "401", description = "Credencial ausente ou recusada")
    @ApiResponse(responseCode = "403", description = "Só o recebedor do pagamento pode devolvê-lo")
    @ApiResponse(responseCode = "404", description = "Pagamento não encontrado")
    @ApiResponse(responseCode = "422", description = "Não liquidado, prazo vencido, valor excedido, saldo...")
    fun devolver(
        @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) credencial: String?,
        @PathVariable endToEndId: String,
        @Valid @RequestBody request: DevolverRequest,
    ): ResponseEntity<DevolucaoResponse> {
        autorizacao.exigirCredencial(credencial)
        val pagamento = pagamentos.consultar(endToEndId)
        // Quem não é parte do pagamento recebe 404, igual ao inexistente (anti-enumeração).
        if (!autorizacao.eDonoDeAlguma(credencial, pagamento.contaOrigemId, pagamento.contaDestinoId)) {
            throw RecursoNaoEncontradoException("PAGAMENTO_NAO_ENCONTRADO", "Pagamento não encontrado")
        }
        // Anti-IDOR: o dinheiro sai da conta do recebedor, então só ele pode pedir (pagador = 403).
        autorizacao.exigirDono(credencial, pagamento.contaDestinoId)

        val (devolucao, repetida) = service.devolver(endToEndId, request.paraComando())
        if (devolucao.estado == EstadoPagamento.REJEITADO) {
            throw RegraNegocioException(
                devolucao.motivoCodigo ?: "DEVOLUCAO_REJEITADA",
                devolucao.motivoMensagem ?: "Devolução recusada",
            )
        }
        val status = if (devolucao.estado == EstadoPagamento.LIQUIDADO) HttpStatus.CREATED else HttpStatus.ACCEPTED
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(devolucao.idDevolucao)
            .toUri()
        return ResponseEntity.status(status)
            .location(location)
            .header(PagamentoPixController.CABECALHO_REPETIDO, repetida.toString())
            .body(DevolucaoResponse.de(devolucao))
    }

    @GetMapping
    @SecurityRequirement(name = OpenApiConfig.BASIC)
    @Operation(summary = "Lista as devoluções de um pagamento")
    @ApiResponse(responseCode = "200", description = "Devoluções em ordem de criação (pode ser vazia)")
    @ApiResponse(responseCode = "404", description = "Pagamento não encontrado (ou de terceiros)")
    fun listar(
        @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) credencial: String?,
        @PathVariable endToEndId: String,
    ): List<DevolucaoResponse> {
        autorizacao.exigirCredencial(credencial)
        val pagamento = pagamentos.consultar(endToEndId)
        if (!autorizacao.eDonoDeAlguma(credencial, pagamento.contaOrigemId, pagamento.contaDestinoId)) {
            throw RecursoNaoEncontradoException("PAGAMENTO_NAO_ENCONTRADO", "Pagamento não encontrado")
        }
        return service.listar(endToEndId).map(DevolucaoResponse::de)
    }
}
