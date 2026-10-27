package br.com.pixsimulado.config

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Teste unitário (sem Spring, sem Docker): garante que a chave da Ledger
 * jamais aparece em texto puro caso o objeto de configuração seja logado.
 */
class LedgerPropertiesTest {

    @Test
    fun `toString mascara a chave de API`() {
        val props = LedgerProperties(baseUrl = "http://ledger:8080", apiKey = "segredo-super-sigiloso")

        val texto = props.toString()

        assertFalse(texto.contains("segredo-super-sigiloso"))
        assertTrue(texto.contains("apiKey=****"))
    }
}
