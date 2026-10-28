package br.com.pixsimulado.pagamento

import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Teste unitário do formato do EndToEndId (manual do Bacen). Sem Spring, sem Docker. */
class EndToEndIdTest {

    private val relogio = Clock.fixed(Instant.parse("2026-10-28T15:30:59Z"), ZoneOffset.UTC)

    @Test
    fun `gera 32 caracteres com E, ISPB, data UTC truncada no minuto e sufixo alfanumerico`() {
        val e2e = EndToEndId.gerar("12345678", relogio).valor

        assertEquals(32, e2e.length)
        assertTrue(e2e.startsWith("E12345678202610281530"), e2e)
        assertTrue(e2e.substring(21).matches(Regex("[A-Za-z0-9]{11}")), e2e)
    }

    @Test
    fun `expoe ISPB e momento de geracao`() {
        val e2e = EndToEndId.gerar("12345678", relogio)

        assertEquals("12345678", e2e.ispb)
        assertEquals(Instant.parse("2026-10-28T15:30:00Z"), e2e.geradoEm)
    }

    @Test
    fun `identificadores gerados no mesmo minuto nao colidem`() {
        val gerados = List(5_000) { EndToEndId.gerar("12345678", relogio).valor }
        assertEquals(gerados.size, gerados.toSet().size)
    }

    @Test
    fun `aceita um identificador valido e ignora espacos nas pontas`() {
        assertEquals("E12345678202610281530aBcDeFgHiJk", EndToEndId.de("  E12345678202610281530aBcDeFgHiJk ").valor)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "E12345678202610281530aBcDeFgHiJ",   // 31 caracteres
            "E12345678202610281530aBcDeFgHiJkL", // 33 caracteres
            "e12345678202610281530aBcDeFgHiJk",  // "e" minúsculo
            "E1234567X202610281530aBcDeFgHiJk",  // ISPB com letra
            "E12345678202613281530aBcDeFgHiJk",  // mês 13
            "E12345678202610281560aBcDeFgHiJk",  // minuto 60
            "E12345678202602301530aBcDeFgHiJk",  // 30 de fevereiro
            "E12345678202610281530aBcDeFgHi-k",  // sufixo com símbolo
        ],
    )
    fun `recusa formatos invalidos`(texto: String) {
        val erro = assertFailsWith<EntradaInvalidaException> { EndToEndId.de(texto) }
        assertEquals("END_TO_END_ID_INVALIDO", erro.codigo)
    }
}
