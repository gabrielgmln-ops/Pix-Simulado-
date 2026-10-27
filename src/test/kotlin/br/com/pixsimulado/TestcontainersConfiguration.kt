package br.com.pixsimulado

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Sobe um PostgreSQL real em contêiner para os testes de integração.
 *
 * @ServiceConnection faz o Spring Boot configurar o datasource automaticamente
 * com URL, usuário e senha do contêiner. Mesma versão do docker-compose,
 * para que o teste reflita o ambiente de execução.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
}
