package br.com.pixsimulado.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Relógio injetável.
 *
 * Todo código de domínio deve obter a hora via [Clock] em vez de chamar Instant.now()
 * diretamente. Assim os testes conseguem fixar o tempo (ex.: validade de QR Code,
 * prazo de devolução, formação do EndToEndId).
 */
@Configuration
class ClockConfig {

    /** Sempre UTC: fuso horário é responsabilidade da camada de apresentação. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
