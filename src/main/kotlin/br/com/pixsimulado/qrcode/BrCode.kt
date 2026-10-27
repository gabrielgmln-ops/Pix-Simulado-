package br.com.pixsimulado.qrcode

import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import java.math.BigDecimal
import java.text.Normalizer

/**
 * BR Code estático (simplificado) do Pix: o texto que vai dentro do QR Code
 * e também é usado no "Pix Copia e Cola".
 *
 * Campos gerados, na ordem do manual do Bacen:
 *   00 Payload Format Indicator   "01"
 *   26 Merchant Account Info Pix  { 00 GUI "br.gov.bcb.pix", 01 chave, 02 descrição? }
 *   52 Merchant Category Code     "0000"
 *   53 Moeda                      "986" (Real, ISO 4217)
 *   54 Valor                      opcional; ausente = pagador digita o valor
 *   58 País                       "BR"
 *   59 Nome do recebedor          até 25 caracteres
 *   60 Cidade do recebedor        até 15 caracteres
 *   62 Dados adicionais           { 05 txid, "***" quando não informado }
 *   63 CRC16                      4 dígitos hexadecimais
 *
 * Fora do escopo desta simplificação: QR dinâmico (campo 25 com URL de cobrança),
 * campo 01 de uso único e templates não-Pix.
 */
data class BrCode(
    val chave: String,
    val valor: BigDecimal?,
    val nomeRecebedor: String,
    val cidade: String,
    val txid: String?,
    val descricao: String?,
) {

    /** Monta o payload completo com CRC. Valida os limites do padrão antes de gerar. */
    fun gerar(): String {
        val contaPix = buildString {
            append(Tlv.campo("00", GUI_PIX))
            append(Tlv.campo("01", chave))
            descricao?.let { append(Tlv.campo("02", it)) }
        }
        if (contaPix.length > 99) {
            throw EntradaInvalidaException(
                "DESCRICAO_MUITO_LONGA",
                "Descrição longa demais para esta chave: o bloco Pix excede 99 caracteres",
            )
        }

        val semCrc = buildString {
            append(Tlv.campo("00", "01"))
            append(Tlv.campo("26", contaPix))
            append(Tlv.campo("52", "0000"))
            append(Tlv.campo("53", MOEDA_BRL))
            valor?.let { append(Tlv.campo("54", formatarValor(it))) }
            append(Tlv.campo("58", "BR"))
            append(Tlv.campo("59", nomeRecebedor))
            append(Tlv.campo("60", cidade))
            append(Tlv.campo("62", Tlv.campo("05", txid ?: TXID_AUSENTE)))
            append("6304") // ID + tamanho do CRC entram no cálculo
        }
        return semCrc + Crc16.calcular(semCrc)
    }

    companion object {
        const val GUI_PIX = "br.gov.bcb.pix"
        const val MOEDA_BRL = "986"
        const val TXID_AUSENTE = "***"
        const val MAX_NOME = 25
        const val MAX_CIDADE = 15

        /** Valor máximo representável no campo 54 (13 caracteres: 10 inteiros + "." + 2). */
        val VALOR_MAXIMO = BigDecimal("9999999999.99")

        private val REGEX_VALOR = Regex("""^\d{1,10}(\.\d{1,2})?$""")

        /**
         * Fábrica que aplica as normalizações exigidas pelo padrão:
         * remove acentos (o BR Code é ASCII) e trunca nome/cidade nos limites.
         */
        fun criar(
            chave: String,
            valor: BigDecimal?,
            nomeRecebedor: String,
            cidade: String,
            txid: String?,
            descricao: String?,
        ) = BrCode(
            chave = chave,
            valor = valor?.let(::validarValor),
            nomeRecebedor = paraAscii(nomeRecebedor).take(MAX_NOME).trim(),
            cidade = paraAscii(cidade).take(MAX_CIDADE).trim(),
            txid = txid,
            descricao = descricao?.let(::paraAscii)?.takeIf { it.isNotBlank() },
        )

        /**
         * Lê e valida um payload: estrutura TLV, CRC, campos obrigatórios e GUI do Pix.
         * Não valida o formato da chave: QR Codes de outras instituições podem trazer
         * tipos que não emitimos (telefone, CNPJ). Quem decide é a consulta à chave.
         */
        fun ler(payload: String): BrCode {
            val texto = payload.trim()

            // 1) Integridade: os 4 últimos caracteres são o CRC do restante.
            if (texto.length < 8 || texto.substring(texto.length - 8, texto.length - 4) != "6304") {
                throw invalido("Campo CRC (63) ausente ou fora da posição final")
            }
            val crcInformado = texto.takeLast(4).uppercase()
            val crcCalculado = Crc16.calcular(texto.dropLast(4))
            if (crcInformado != crcCalculado) {
                throw EntradaInvalidaException(
                    "QRCODE_CRC_INVALIDO",
                    "CRC do QR Code não confere (informado $crcInformado, calculado $crcCalculado)",
                )
            }

            // 2) Estrutura.
            val campos = decodificar(texto).toMap()
            if (campos["00"] != "01") throw invalido("Payload Format Indicator (00) deve ser 01")
            if (campos["53"] != MOEDA_BRL) throw invalido("Moeda (53) deve ser 986 (BRL)")
            if (campos["58"] != "BR") throw invalido("País (58) deve ser BR")

            val contaPix = decodificar(campos["26"] ?: throw invalido("Campo Pix (26) ausente")).toMap()
            if (!contaPix["00"].equals(GUI_PIX, ignoreCase = true)) throw invalido("GUI do arranjo Pix ausente ou inválido")

            val txid = campos["62"]?.let { decodificar(it).toMap()["05"] }

            return BrCode(
                chave = contaPix["01"] ?: throw invalido("Chave Pix (26.01) ausente"),
                valor = campos["54"]?.let(::lerValor),
                nomeRecebedor = campos["59"] ?: throw invalido("Nome do recebedor (59) ausente"),
                cidade = campos["60"] ?: throw invalido("Cidade do recebedor (60) ausente"),
                txid = txid?.takeIf { it != TXID_AUSENTE },
                descricao = contaPix["02"],
            )
        }

        /** Remove acentos e qualquer caractere fora do ASCII imprimível. */
        internal fun paraAscii(texto: String): String =
            Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")       // marcas diacríticas (á -> a)
                .replace(Regex("[^\\x20-\\x7E]"), "") // demais caracteres não ASCII
                .trim()

        /** Dinheiro nunca é arredondado silenciosamente: mais de 2 casas é erro. */
        private fun validarValor(valor: BigDecimal): BigDecimal {
            if (valor.signum() <= 0) throw EntradaInvalidaException("VALOR_INVALIDO", "Valor deve ser positivo")
            if (valor.stripTrailingZeros().scale() > 2) {
                throw EntradaInvalidaException("VALOR_INVALIDO", "Valor deve ter no máximo 2 casas decimais")
            }
            if (valor > VALOR_MAXIMO) throw EntradaInvalidaException("VALOR_INVALIDO", "Valor acima do máximo $VALOR_MAXIMO")
            return valor.setScale(2)
        }

        private fun formatarValor(valor: BigDecimal): String = valor.setScale(2).toPlainString()

        private fun lerValor(texto: String): BigDecimal {
            if (!REGEX_VALOR.matches(texto)) throw invalido("Valor (54) em formato inválido: $texto")
            return validarValor(BigDecimal(texto))
        }

        private fun decodificar(texto: String) =
            try {
                Tlv.decodificar(texto)
            } catch (e: IllegalArgumentException) {
                throw invalido(e.message ?: "Estrutura TLV inválida")
            }

        private fun invalido(mensagem: String) = EntradaInvalidaException("QRCODE_INVALIDO", mensagem)
    }
}
