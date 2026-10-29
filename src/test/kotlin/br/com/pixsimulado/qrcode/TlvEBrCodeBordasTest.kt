package br.com.pixsimulado.qrcode

import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Bordas da codificação TLV e da leitura de BR Code: payloads com CRC CORRETO, mas
 * estrutura errada. Garante que a recusa vem da validação dos campos, e não só do CRC
 * (um atacante consegue recalcular o CRC de um payload adulterado).
 */
class TlvEBrCodeBordasTest {

    // ===================== TLV =====================

    @ParameterizedTest
    @ValueSource(strings = ["5", "ab", "123"])
    fun `campo com ID que nao tem 2 digitos e recusado`(id: String) {
        assertFailsWith<IllegalArgumentException> { Tlv.campo(id, "X") }
    }

    @Test
    fun `campo vazio ou com mais de 99 caracteres e recusado`() {
        assertFailsWith<IllegalArgumentException> { Tlv.campo("01", "") }
        assertFailsWith<IllegalArgumentException> { Tlv.campo("01", "x".repeat(100)) }
        assertEquals("0199" + "x".repeat(99), Tlv.campo("01", "x".repeat(99)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["000", "AB02BR", "58XXBR", "5805BR"])
    fun `estrutura TLV corrompida e recusada`(texto: String) {
        assertFailsWith<IllegalArgumentException> { Tlv.decodificar(texto) }
    }

    @Test
    fun `decodifica campos em ordem`() {
        assertEquals(listOf("58" to "BR", "53" to "986"), Tlv.decodificar("5802BR5303986"))
    }

    // ===================== BR Code =====================

    @Test
    fun `payload Format Indicator diferente de 01 e recusado`() {
        assertRecusado(payload(pfi = "02"), "Payload Format Indicator")
    }

    @Test
    fun `moeda diferente de BRL e recusada`() {
        assertRecusado(payload(moeda = "840"), "Moeda")
    }

    @Test
    fun `pais diferente de BR e recusado`() {
        assertRecusado(payload(pais = "US"), "País")
    }

    @Test
    fun `sem o campo Pix 26 e recusado`() {
        assertRecusado(payload(contaPix = null), "Campo Pix (26) ausente")
    }

    @Test
    fun `GUI de outro arranjo e recusado`() {
        assertRecusado(payload(contaPix = Tlv.campo("00", "br.com.outro") + Tlv.campo("01", "a@b.com")), "GUI")
    }

    @Test
    fun `sem chave Pix e recusado`() {
        assertRecusado(payload(contaPix = Tlv.campo("00", "br.gov.bcb.pix")), "Chave Pix (26.01) ausente")
    }

    @Test
    fun `payload curto demais para ter CRC e recusado`() {
        assertFailsWith<EntradaInvalidaException> { BrCode.ler("6304") }
    }

    @Test
    fun `payload valido montado a mao e lido com chave e sem valor`() {
        val lido = BrCode.ler(payload())
        assertEquals("loja@exemplo.com", lido.chave)
        assertEquals(null, lido.valor)
    }

    // ===================== Apoio =====================

    /** Monta um BR Code com CRC correto, permitindo estragar cada campo. */
    private fun payload(
        pfi: String = "01",
        contaPix: String? = Tlv.campo("00", "br.gov.bcb.pix") + Tlv.campo("01", "loja@exemplo.com"),
        moeda: String = "986",
        pais: String = "BR",
    ): String {
        val corpo = buildString {
            append(Tlv.campo("00", pfi))
            contaPix?.let { append(Tlv.campo("26", it)) }
            append(Tlv.campo("52", "0000"))
            append(Tlv.campo("53", moeda))
            append(Tlv.campo("58", pais))
            append(Tlv.campo("59", "LOJA SINTETICA"))
            append(Tlv.campo("60", "SAO PAULO"))
            append("6304")
        }
        return corpo + Crc16.calcular(corpo)
    }

    private fun assertRecusado(payload: String, trechoDaMensagem: String) {
        val erro = assertFailsWith<EntradaInvalidaException> { BrCode.ler(payload) }
        assert(erro.message!!.contains(trechoDaMensagem)) { "mensagem inesperada: ${erro.message}" }
    }
}
