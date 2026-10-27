package br.com.pixsimulado.qrcode

/**
 * CRC16-CCITT-FALSE, exigido pelo campo 63 do BR Code.
 *
 * Parâmetros: polinômio 0x1021, valor inicial 0xFFFF, sem reflexão e sem XOR final.
 * O cálculo cobre todo o payload até o cabeçalho "6304", inclusive.
 * Valor de referência do algoritmo: "123456789" -> 29B1.
 */
object Crc16 {

    private const val POLINOMIO = 0x1021
    private const val INICIAL = 0xFFFF

    /** Retorna o CRC em 4 dígitos hexadecimais maiúsculos, como o BR Code exige. */
    fun calcular(dados: String): String {
        var crc = INICIAL
        for (byte in dados.toByteArray(Charsets.US_ASCII)) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor POLINOMIO else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return "%04X".format(crc)
    }
}
