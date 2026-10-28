package br.com.pixsimulado.pagamento

import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Identificador de uma operação Pix, no formato do manual do Bacen (32 caracteres):
 *
 *   E  99999999  202610281530  a1B2c3D4e5F
 *   |  |         |             +-- 11 alfanuméricos aleatórios (unicidade)
 *   |  |         +---------------- data e hora UTC, yyyyMMddHHmm
 *   |  +-------------------------- ISPB do PSP que gerou o identificador (8 dígitos)
 *   +----------------------------- tipo: E = pagamento (EndToEndId), D = devolução
 *
 * É gerado pela instituição, nunca escolhido livremente pelo cliente, e é ao mesmo
 * tempo a chave de idempotência do Pix e o Idempotency-Key enviado à Ledger. Como o
 * prefixo faz parte do valor, a devolução nunca colide com o pagamento que ela desfaz.
 */
@JvmInline
value class EndToEndId private constructor(val valor: String) {

    /** Tipo da operação ('E' ou 'D'). */
    val prefixo: Char get() = valor[0]

    /** ISPB da instituição que gerou o identificador. */
    val ispb: String get() = valor.substring(1, 9)

    /** Momento (truncado no minuto) em que o identificador foi gerado. */
    val geradoEm: Instant
        get() = LocalDateTime.parse(valor.substring(9, 21), FORMATO_DATA).toInstant(ZoneOffset.UTC)

    override fun toString(): String = valor

    companion object {
        /** Prefixo do pagamento (EndToEndId). */
        const val PAGAMENTO = 'E'

        /** Prefixo da devolução (identificador de devolução). */
        const val DEVOLUCAO = 'D'

        private const val ALFANUMERICOS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val CORPO = Regex("^\\d{8}\\d{12}[A-Za-z0-9]{11}$")

        // "uuuu" + STRICT: recusa datas impossíveis (mês 13, dia 32) em vez de "ajustá-las".
        private val FORMATO_DATA = DateTimeFormatter.ofPattern("uuuuMMddHHmm").withResolverStyle(ResolverStyle.STRICT)

        // SecureRandom: o sufixo não pode ser previsível, senão um terceiro adivinharia
        // o identificador de outra operação e leria o resultado dela pela repetição idempotente.
        private val aleatorio = SecureRandom()

        fun gerar(ispb: String, clock: Clock, prefixo: Char = PAGAMENTO): EndToEndId {
            require(ispb.matches(Regex("\\d{8}"))) { "ISPB deve ter 8 dígitos" }
            require(prefixo == PAGAMENTO || prefixo == DEVOLUCAO) { "Prefixo deve ser E ou D" }
            val agora = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            val sufixo = buildString { repeat(11) { append(ALFANUMERICOS[aleatorio.nextInt(ALFANUMERICOS.length)]) } }
            return EndToEndId("$prefixo$ispb${FORMATO_DATA.format(agora)}$sufixo")
        }

        /** Valida prefixo, formato e data. ISPB e prazo são regras do serviço, não do formato. */
        fun de(texto: String, prefixo: Char = PAGAMENTO): EndToEndId {
            val valor = texto.trim()
            if (valor.length != 32 || valor[0] != prefixo || !CORPO.matches(valor.substring(1))) throw invalido(prefixo)
            try {
                LocalDateTime.parse(valor.substring(9, 21), FORMATO_DATA)
            } catch (e: DateTimeParseException) {
                throw invalido(prefixo)
            }
            return EndToEndId(valor)
        }

        private fun invalido(prefixo: Char) =
            if (prefixo == DEVOLUCAO) {
                EntradaInvalidaException(
                    "ID_DEVOLUCAO_INVALIDO",
                    "Identificador de devolução deve ter 32 caracteres: D + ISPB (8 dígitos) + yyyyMMddHHmm UTC + 11 alfanuméricos",
                )
            } else {
                EntradaInvalidaException(
                    "END_TO_END_ID_INVALIDO",
                    "EndToEndId deve ter 32 caracteres: E + ISPB (8 dígitos) + yyyyMMddHHmm UTC + 11 alfanuméricos",
                )
            }
    }
}
