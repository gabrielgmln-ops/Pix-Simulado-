package br.com.pixsimulado.qrcode

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class Crc16Test {

    @Test
    fun `valor de referencia do CRC16-CCITT-FALSE`() {
        // Vetor de verificação padrão do algoritmo ("check value").
        assertEquals("29B1", Crc16.calcular("123456789"))
    }

    @Test
    fun `sempre devolve 4 digitos hexadecimais maiusculos`() {
        assertEquals("FFFF", Crc16.calcular(""))
    }
}
