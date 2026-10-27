package br.com.pixsimulado

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Base dos testes de integração.
 *
 * Todas as subclasses compartilham exatamente a mesma configuração, o que permite ao
 * Spring reaproveitar um único contexto (e um único contêiner PostgreSQL) para a
 * suíte inteira, mantendo os testes rápidos.
 *
 * disabledWithoutDocker = true: sem Docker, os testes são ignorados em vez de falhar.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
abstract class IntegrationTest
