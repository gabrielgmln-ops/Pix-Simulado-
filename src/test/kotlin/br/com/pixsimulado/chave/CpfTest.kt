package br.com.pixsimulado.chave

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** CPFs usados aqui são sintéticos: dígitos verificadores calculados, sem titular real. */
class CpfTest {

    @ParameterizedTest
    @ValueSource(strings = ["12345678909", "123.456.789-09", " 123.456.789-09 ", "123456789-09"])
    fun `aceita CPF valido com ou sem pontuacao e normaliza para 11 digitos`(entrada: String) {
        assertEquals("12345678909", Cpf.normalizar(entrada))
    }

    @Test
    fun `aceita CPF cujo digito verificador calculado e 10 e vira 0`() {
        // 98765432100: o 1º dígito verificador resulta em resto 10 -> 0.
        assertEquals("98765432100", Cpf.normalizar("98765432100"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "12345678900",     // dígito verificador errado
            "12345678919",     // segundo dígito errado
            "11111111111",     // sequência repetida (passa no módulo 11, mas é inválida)
            "00000000000",
            "1234567890",      // 10 dígitos
            "123456789012",    // 12 dígitos
            "123.456.789/09",  // separador inválido
            "abc.def.ghi-jk",
            "",
        ],
    )
    fun `rejeita CPF invalido`(entrada: String) {
        assertNull(Cpf.normalizar(entrada))
    }

    @Test
    fun `mascara exibe apenas os seis digitos centrais`() {
        assertEquals("***.456.789-**", Cpf.mascarar("12345678909"))
    }
}
