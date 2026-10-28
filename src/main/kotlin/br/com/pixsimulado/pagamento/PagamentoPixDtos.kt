package br.com.pixsimulado.pagamento

import br.com.pixsimulado.chave.UUID_REGEX
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/*
 * DTOs da API de pagamentos. Como nas chaves, os campos de entrada são anuláveis para
 * que a ausência chegue ao Bean Validation e gere erro por campo (400 com lista).
 */

@Schema(description = "EndToEndId gerado pela instituição para um novo pagamento")
data class EndToEndIdResponse(
    @Schema(example = "E9999999920261028153012aBcDeFgHiJk")
    val endToEndId: String,
)

@Schema(description = "Pagamento Pix por chave ou por QR Code (informe exatamente um dos dois)")
data class PagarRequest(
    @field:NotBlank
    @Schema(description = "Obtido em POST /api/v1/pagamentos/end-to-end-id; repita-o para repetir o pedido com segurança")
    val endToEndId: String?,

    @field:NotBlank
    @field:Pattern(regexp = UUID_REGEX, message = "deve ser o UUID da conta na Ledger API")
    @Schema(description = "Conta pagadora na Ledger", example = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b")
    val contaOrigemId: String?,

    @Schema(description = "Chave Pix do recebedor", example = "loja.sintetica@exemplo.com")
    val chave: String?,

    @Schema(description = "Payload do QR Code (Pix Copia e Cola)")
    val qrPayload: String?,

    // @Digits recusa 10.001 com 400: fração de centavo nunca é arredondada em silêncio.
    @field:Positive
    @field:Digits(integer = 17, fraction = 2)
    @Schema(description = "Obrigatório, a menos que o QR Code já defina o valor", example = "25.90")
    val valor: BigDecimal?,

    @field:Size(max = 140)
    val descricao: String?,
) {
    fun paraComando() = NovoPagamento(
        endToEndId = endToEndId!!, // !! seguro: garantido pelo @NotBlank validado antes
        contaOrigemId = UUID.fromString(contaOrigemId!!),
        chave = chave?.takeIf { it.isNotBlank() },
        qrPayload = qrPayload?.takeIf { it.isNotBlank() },
        valor = valor,
        descricao = descricao?.trim()?.takeIf { it.isNotEmpty() },
    )
}

@Schema(description = "Pagamento Pix")
data class PagamentoResponse(
    val endToEndId: String,
    @Schema(description = "PENDENTE, LIQUIDADO, REJEITADO ou INCERTO (aguardando reconciliação)")
    val estado: EstadoPagamento,
    val contaOrigemId: UUID,
    val contaDestinoId: UUID,
    val chaveDestino: String,
    val valor: BigDecimal,
    @Schema(description = "Soma das devoluções já liquidadas (o pagamento em si nunca muda)")
    val valorDevolvido: BigDecimal,
    val descricao: String?,
    @Schema(description = "Id da transação na Ledger (somente LIQUIDADO)")
    val ledgerTransacaoId: UUID?,
    val motivo: MotivoResponse?,
    val criadoEm: Instant,
    val atualizadoEm: Instant,
) {
    companion object {
        fun de(p: PagamentoPix, valorDevolvido: BigDecimal) = PagamentoResponse(
            endToEndId = p.endToEndId,
            estado = p.estado,
            contaOrigemId = p.contaOrigemId,
            contaDestinoId = p.contaDestinoId,
            chaveDestino = p.chaveDestino,
            valor = p.valor,
            valorDevolvido = valorDevolvido.setScale(2),
            descricao = p.descricao,
            ledgerTransacaoId = p.ledgerTransacaoId,
            motivo = p.motivoCodigo?.let { MotivoResponse(it, p.motivoMensagem.orEmpty()) },
            criadoEm = p.criadoEm,
            atualizadoEm = p.atualizadoEm,
        )
    }
}

data class MotivoResponse(val codigo: String, val mensagem: String)
