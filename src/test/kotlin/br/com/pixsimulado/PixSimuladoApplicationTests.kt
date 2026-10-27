package br.com.pixsimulado

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Teste de fumaça da Etapa 0: a aplicação sobe por completo contra um PostgreSQL real,
 * o Flyway aplica as migrações e os endpoints de infraestrutura respondem.
 *
 * disabledWithoutDocker = true: em máquinas sem Docker o teste é ignorado (não falha);
 * no CI (Etapa 5) o Docker estará sempre disponível.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class PixSimuladoApplicationTests {

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
    fun `flyway aplica a migracao baseline`() {
        val aplicadas = flyway.info().applied().map { it.version.version }
        assertTrue("1" in aplicadas, "Migração V1 deveria estar aplicada; aplicadas=$aplicadas")
    }

    @Test
    fun `documentacao openapi esta publicada`() {
        val resposta = rest.getForEntity("/v3/api-docs", String::class.java)
        assertEquals(HttpStatus.OK, resposta.statusCode)
        assertTrue(resposta.body!!.contains("Pix Simulado"))
    }
}
