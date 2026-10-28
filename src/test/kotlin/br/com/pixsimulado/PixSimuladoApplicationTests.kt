package br.com.pixsimulado

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Teste de fumaça da Etapa 0: a aplicação sobe por completo contra um PostgreSQL real,
 * o Flyway aplica as migrações e os endpoints de infraestrutura respondem.
 */
class PixSimuladoApplicationTests : IntegrationTest() {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var flyway: Flyway

    @Test
    fun `health check responde UP`() {
        val resposta = rest.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, resposta.statusCode)
        assertTrue(resposta.body!!.contains("\"UP\""))
    }

    @Test
    fun `flyway aplica todas as migracoes`() {
        val aplicadas = flyway.info().applied().map { it.version.version }
        assertTrue(aplicadas.containsAll(listOf("1", "2", "3", "4")), "Migrações esperadas não aplicadas; aplicadas=$aplicadas")
    }

    @Test
    fun `documentacao openapi esta publicada`() {
        val resposta = rest.getForEntity("/v3/api-docs", String::class.java)
        assertEquals(HttpStatus.OK, resposta.statusCode)
        assertTrue(resposta.body!!.contains("Pix Simulado"))
    }
}
