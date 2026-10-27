package br.com.pixsimulado.qrcode

import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrCodeTest {

    /** Exemplo de BR Code estático publicado no manual do Bacen (dados fictícios). */
    private val exemploManualBacen =
        "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-426655440000" +
            "5204000053039865802BR5913Fulano de Tal6008BRASILIA62070503***63041D3D"

    @Test
    fun `le o exemplo oficial do manual do Bacen`() {
        val brCode = BrCode.ler(exemploManualBacen)

        assertEquals("123e4567-e12b-12d1-a456-426655440000", brCode.chave)
        assertEquals("Fulano de Tal", brCode.nomeRecebedor)
        assertEquals("BRASILIA", brCode.cidade)
        assertNull(brCode.valor)
        assertNull(brCode.txid, "txid '***' significa ausente")
    }

    @Test
    fun `gera exatamente o payload do exemplo oficial`() {
        val brCode = BrCode.criar(
            chave = "123e4567-e12b-12d1-a456-426655440000",
            valor = null,
            nomeRecebedor = "Fulano de Tal",
            cidade = "BRASILIA",
            txid = null,
            descricao = null,
        )
        assertEquals(exemploManualBacen, brCode.gerar())
    }

    @Test
    fun `ida e volta preserva todos os campos, com valor em BigDecimal de 2 casas`() {
        val original = BrCode.criar(
            chave = "fulano@exemplo.com",
            valor = BigDecimal("1234.5"),
            nomeRecebedor = "Fulano Sintetico",
            cidade = "SAO PAULO",
            txid = "PEDIDO123",
            descricao = "Pedido 123",
        )

        val payload = original.gerar()
        val lido = BrCode.ler(payload)

        assertTrue("54071234.50" in payload, "valor deve ser serializado como 1234.50")
        assertEquals(original, lido)
        assertEquals(BigDecimal("1234.50"), lido.valor)
    }

    @Test
    fun `remove acentos e trunca nome e cidade nos limites do padrao`() {
        val brCode = BrCode.criar(
            chave = "fulano@exemplo.com",
            valor = null,
            nomeRecebedor = "João da Conceição Assunção Brandão",
            cidade = "São José dos Campos",
            txid = null,
            descricao = "Promoção",
        )

        assertEquals("Joao da Conceicao Assunca", brCode.nomeRecebedor) // 25 caracteres
        assertEquals("Sao Jose dos Ca", brCode.cidade)                   // 15 caracteres
        assertEquals("Promocao", brCode.descricao)
    }

    @Test
    fun `rejeita valor com mais de 2 casas decimais em vez de arredondar`() {
        val erro = assertThrows<EntradaInvalidaException> {
            BrCode.criar("a@b.com", BigDecimal("10.005"), "Nome", "Cidade", null, null)
        }
        assertEquals("VALOR_INVALIDO", erro.codigo)
    }

    @Test
    fun `aceita valor com zeros a direita alem de 2 casas`() {
        val brCode = BrCode.criar("a@b.com", BigDecimal("10.5000"), "Nome", "Cidade", null, null)
        assertEquals(BigDecimal("10.50"), brCode.valor)
    }

    @Test
    fun `rejeita valor zero ou negativo`() {
        assertThrows<EntradaInvalidaException> { BrCode.criar("a@b.com", BigDecimal.ZERO, "N", "C", null, null) }
        assertThrows<EntradaInvalidaException> { BrCode.criar("a@b.com", BigDecimal("-1"), "N", "C", null, null) }
    }

    @Test
    fun `rejeita descricao que estoura o bloco Pix de 99 caracteres`() {
        val chaveLonga = "a".repeat(60) + "@exemplo.com"
        val erro = assertThrows<EntradaInvalidaException> {
            BrCode.criar(chaveLonga, null, "Nome", "Cidade", null, "Descricao com vinte+").gerar()
        }
        assertEquals("DESCRICAO_MUITO_LONGA", erro.codigo)
    }

    @Test
    fun `detecta payload adulterado pelo CRC`() {
        // Troca o nome do recebedor mantendo o CRC antigo: ataque clássico de QR falso.
        val adulterado = exemploManualBacen.replace("Fulano de Tal", "Golpista  Tal")

        val erro = assertThrows<EntradaInvalidaException> { BrCode.ler(adulterado) }
        assertEquals("QRCODE_CRC_INVALIDO", erro.codigo)
    }

    @Test
    fun `aceita CRC informado em minusculas`() {
        BrCode.ler(exemploManualBacen.dropLast(4) + "1d3d")
    }

    @Test
    fun `rejeita payload sem campo CRC`() {
        val erro = assertThrows<EntradaInvalidaException> { BrCode.ler("000201") }
        assertEquals("QRCODE_INVALIDO", erro.codigo)
    }

    @Test
    fun `rejeita payload de outro arranjo (GUI diferente de br gov bcb pix)`() {
        val semCrc = Tlv.campo("00", "01") +
            Tlv.campo("26", Tlv.campo("00", "com.outro.arranjo") + Tlv.campo("01", "x")) +
            Tlv.campo("52", "0000") + Tlv.campo("53", "986") + Tlv.campo("58", "BR") +
            Tlv.campo("59", "Nome") + Tlv.campo("60", "Cidade") + "6304"

        val erro = assertThrows<EntradaInvalidaException> { BrCode.ler(semCrc + Crc16.calcular(semCrc)) }
        assertEquals("QRCODE_INVALIDO", erro.codigo)
    }

    @Test
    fun `rejeita estrutura TLV corrompida mesmo com CRC correto`() {
        // Campo 59 declara 40 caracteres, mas o payload acaba antes.
        val semCrc = "000201" + "5940Nome" + "6304"
        val erro = assertThrows<EntradaInvalidaException> { BrCode.ler(semCrc + Crc16.calcular(semCrc)) }
        assertEquals("QRCODE_INVALIDO", erro.codigo)
    }
}
