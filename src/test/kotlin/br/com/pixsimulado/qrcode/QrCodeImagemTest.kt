package br.com.pixsimulado.qrcode

import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals

class QrCodeImagemTest {

    private val payload = BrCode.criar("fulano@exemplo.com", null, "Fulano", "SAO PAULO", "TX1", null).gerar()

    @Test
    fun `gera PNG com o tamanho solicitado e o le de volta`() {
        val png = QrCodeImagem.gerarPng(payload, 400)

        val imagem = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(400, imagem.width)
        assertEquals(400, imagem.height)
        assertEquals(payload, QrCodeImagem.lerTexto(png))
    }

    @Test
    fun `arquivo que nao e imagem e rejeitado`() {
        val erro = assertThrows<EntradaInvalidaException> { QrCodeImagem.lerTexto("não sou imagem".toByteArray()) }
        assertEquals("IMAGEM_INVALIDA", erro.codigo)
    }

    @Test
    fun `imagem sem QR Code e rejeitada`() {
        val branca = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run { color = java.awt.Color.WHITE; fillRect(0, 0, 200, 200); dispose() }
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(branca, "PNG", it) }.toByteArray()

        val erro = assertThrows<EntradaInvalidaException> { QrCodeImagem.lerTexto(png) }
        assertEquals("QRCODE_NAO_ENCONTRADO", erro.codigo)
    }
}
