package br.com.pixsimulado.chave

import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class TipoChaveTest {

    // ---------- E-mail ----------

    @Test
    fun `email e normalizado para minusculas e sem espacos`() {
        assertEquals("fulano.sintetico@exemplo.com.br", TipoChave.EMAIL.normalizar("  Fulano.Sintetico@Exemplo.COM.br "))
    }

    @Test
    fun `email com exatamente 77 caracteres e aceito`() {
        val email = "a".repeat(77 - "@exemplo.com".length) + "@exemplo.com"
        assertEquals(77, email.length)
        assertEquals(email, TipoChave.EMAIL.normalizar(email))
    }

    @Test
    fun `email com mais de 77 caracteres e rejeitado`() {
        val email = "a".repeat(78 - "@exemplo.com".length) + "@exemplo.com"
        assertThrows<EntradaInvalidaException> { TipoChave.EMAIL.normalizar(email) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sem-arroba.com", "fulano@", "@exemplo.com", "fulano@exemplo", "ful ano@exemplo.com", "fulano@-exemplo.com"])
    fun `email em formato invalido e rejeitado`(entrada: String) {
        val erro = assertThrows<EntradaInvalidaException> { TipoChave.EMAIL.normalizar(entrada) }
        assertEquals("CHAVE_INVALIDA", erro.codigo)
    }

    // ---------- CPF ----------

    @Test
    fun `cpf como chave e normalizado`() {
        assertEquals("12345678909", TipoChave.CPF.normalizar("123.456.789-09"))
    }

    @Test
    fun `cpf invalido como chave e rejeitado`() {
        assertThrows<EntradaInvalidaException> { TipoChave.CPF.normalizar("123.456.789-00") }
    }

    // ---------- Aleatória ----------

    @Test
    fun `chave aleatoria uuid v4 e normalizada para minusculas`() {
        assertEquals(
            "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b",
            TipoChave.ALEATORIA.normalizar("3F2A1B4C-5D6E-4F70-8A9B-0C1D2E3F4A5B"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "3f2a1b4c-5d6e-1f70-8a9b-0c1d2e3f4a5b", // versão 1, não 4
            "3f2a1b4c-5d6e-4f70-ca9b-0c1d2e3f4a5b", // variante inválida
            "3f2a1b4c5d6e4f708a9b0c1d2e3f4a5b",     // sem hífens
        ],
    )
    fun `chave aleatoria fora do padrao uuid v4 e rejeitada`(entrada: String) {
        assertThrows<EntradaInvalidaException> { TipoChave.ALEATORIA.normalizar(entrada) }
    }

    // ---------- Inferência ----------

    @Test
    fun `infere o tipo a partir do valor digitado`() {
        assertEquals(TipoChave.CPF to "12345678909", TipoChave.identificar("123.456.789-09"))
        assertEquals(TipoChave.EMAIL to "a@b.com", TipoChave.identificar("A@B.com"))
        assertEquals(
            TipoChave.ALEATORIA to "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b",
            TipoChave.identificar("3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b"),
        )
    }

    @Test
    fun `valor sem formato reconhecivel e rejeitado`() {
        assertThrows<EntradaInvalidaException> { TipoChave.inferir("qualquer-coisa") }
    }
}
