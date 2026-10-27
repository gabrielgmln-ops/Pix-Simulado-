package br.com.pixsimulado.qrcode

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.Base64

@Schema(description = "Geração de QR Code estático")
data class GerarQrCodeRequest(
    @field:NotBlank
    @Schema(description = "Chave Pix ativa do recebedor", example = "fulano.sintetico@exemplo.com")
    val chave: String?,

    @field:DecimalMin("0.01")
    @field:DecimalMax("9999999999.99")
    @field:Digits(integer = 10, fraction = 2)
    @Schema(description = "Valor fixo. Ausente = pagador informa o valor.", example = "25.90")
    val valor: BigDecimal?,

    @field:Size(max = 72)
    @Schema(description = "Mensagem ao pagador (acentos são removidos)", example = "Pedido 123")
    val descricao: String?,

    @field:Pattern(regexp = "^[A-Za-z0-9]{1,25}$", message = "deve ter de 1 a 25 caracteres alfanuméricos")
    @Schema(description = "Identificador da transação para conciliação", example = "PEDIDO123")
    val txid: String?,

    @field:Min(QrCodeImagem.TAMANHO_MINIMO.toLong())
    @field:Max(QrCodeImagem.TAMANHO_MAXIMO.toLong())
    @Schema(description = "Lado da imagem PNG em pixels", example = "300")
    val tamanho: Int?,
) {
    fun paraComando() = NovoQrCode(chave = chave!!, valor = valor, descricao = descricao, txid = txid)
}

@Schema(description = "QR Code gerado")
data class QrCodeGeradoResponse(
    @Schema(description = "BR Code (Pix Copia e Cola)")
    val payload: String,
    @Schema(description = "Imagem PNG em Base64, pronta para <img src=\"data:image/png;base64,...\">")
    val imagemPngBase64: String,
    val dados: BrCodeResponse,
) {
    companion object {
        fun de(gerado: QrCodeGerado, png: ByteArray) = QrCodeGeradoResponse(
            payload = gerado.payload,
            imagemPngBase64 = Base64.getEncoder().encodeToString(png),
            dados = BrCodeResponse.de(gerado.brCode),
        )
    }
}

@Schema(description = "Leitura de payload")
data class LerQrCodeRequest(
    @field:NotBlank
    @field:Size(max = 512)
    @Schema(description = "BR Code (Pix Copia e Cola)")
    val payload: String?,
)

@Schema(description = "Conteúdo de um BR Code")
data class BrCodeResponse(
    val chave: String,
    @Schema(description = "Ausente quando o pagador define o valor")
    val valor: BigDecimal?,
    val nomeRecebedor: String,
    val cidade: String,
    val txid: String?,
    val descricao: String?,
) {
    companion object {
        fun de(brCode: BrCode) = BrCodeResponse(
            chave = brCode.chave,
            valor = brCode.valor,
            nomeRecebedor = brCode.nomeRecebedor,
            cidade = brCode.cidade,
            txid = brCode.txid,
            descricao = brCode.descricao,
        )
    }
}
