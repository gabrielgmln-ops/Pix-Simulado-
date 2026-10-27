package br.com.pixsimulado.qrcode

import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Conversão entre payload (texto) e imagem PNG de QR Code, usando a biblioteca ZXing.
 */
object QrCodeImagem {

    const val TAMANHO_PADRAO = 300
    const val TAMANHO_MINIMO = 150
    const val TAMANHO_MAXIMO = 1000

    /**
     * Gera um PNG quadrado de [tamanho] pixels.
     *  - Correção de erro "M" (~15%): tolera reflexo e pequenos danos na leitura pela câmera.
     *  - Margem de 2 módulos (a "zona de silêncio" em branco ao redor do código).
     */
    fun gerarPng(payload: String, tamanho: Int = TAMANHO_PADRAO): ByteArray {
        require(tamanho in TAMANHO_MINIMO..TAMANHO_MAXIMO) { "Tamanho fora do intervalo permitido" }
        val dicas = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matriz = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, tamanho, tamanho, dicas)
        return ByteArrayOutputStream().use { saida ->
            MatrixToImageWriter.writeToStream(matriz, "PNG", saida)
            saida.toByteArray()
        }
    }

    /** Extrai o texto de uma imagem (PNG/JPEG) contendo um QR Code. */
    fun lerTexto(imagem: ByteArray): String {
        val buffer = ImageIO.read(ByteArrayInputStream(imagem))
            ?: throw EntradaInvalidaException("IMAGEM_INVALIDA", "Arquivo não é uma imagem suportada (PNG ou JPEG)")
        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(buffer)))
        val dicas = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        return try {
            MultiFormatReader().decode(bitmap, dicas).text
        } catch (e: NotFoundException) {
            throw EntradaInvalidaException("QRCODE_NAO_ENCONTRADO", "Nenhum QR Code legível foi encontrado na imagem")
        }
    }
}
