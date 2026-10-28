package br.com.pixsimulado.config

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Teste unitário (sem Spring, sem Docker): garante que a senha da Ledger
 * jamais aparece em texto puro caso o objeto de configuração seja logado.
 */
class LedgerPropertiesTest {

    @Test
    fun `toString mascara a senha`() {
        val props = LedgerProperties(baseUrl = "http://ledger:8080", usuario = "pix-operador", senha = "segredo-super-sigiloso")

        val texto = props.toString()

        assertFalse(texto.contains("segredo-super-sigiloso"))
        assertTrue(texto.contains("senha=****"))
    }
}
