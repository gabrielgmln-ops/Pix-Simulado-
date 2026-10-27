package br.com.pixsimulado.qrcode

/**
 * Codificação TLV (Tag-Length-Value) do padrão EMV QRCPS, base do BR Code.
 *
 * Cada campo é: ID com 2 dígitos + tamanho com 2 dígitos + valor.
 * Ex.: "5802BR" -> ID 58 (país), tamanho 02, valor "BR".
 * O tamanho máximo de um valor é, portanto, 99 caracteres.
 */
internal object Tlv {

    fun campo(id: String, valor: String): String {
        require(id.length == 2 && id.all(Char::isDigit)) { "ID EMV inválido: $id" }
        require(valor.length in 1..99) { "Campo $id com tamanho ${valor.length} fora do limite EMV (1..99)" }
        return id + "%02d".format(valor.length) + valor
    }

    /**
     * Decompõe uma sequência TLV em pares (id, valor), preservando a ordem.
     * Lança [IllegalArgumentException] se a estrutura estiver corrompida.
     */
    fun decodificar(texto: String): List<Pair<String, String>> {
        val campos = mutableListOf<Pair<String, String>>()
        var pos = 0
        while (pos < texto.length) {
            require(pos + 4 <= texto.length) { "Cabeçalho TLV incompleto na posição $pos" }
            val id = texto.substring(pos, pos + 2)
            val tamanhoTexto = texto.substring(pos + 2, pos + 4)
            require(id.all(Char::isDigit) && tamanhoTexto.all(Char::isDigit)) {
                "Cabeçalho TLV não numérico na posição $pos"
            }
            val tamanho = tamanhoTexto.toInt()
            val inicioValor = pos + 4
            require(inicioValor + tamanho <= texto.length) { "Campo $id declara $tamanho caracteres, mas o payload termina antes" }
            campos += id to texto.substring(inicioValor, inicioValor + tamanho)
            pos = inicioValor + tamanho
        }
        return campos
    }
}
