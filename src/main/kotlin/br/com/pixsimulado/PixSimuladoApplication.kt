package br.com.pixsimulado

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Ponto de entrada do Pix Simulado.
 *
 * Serviço de pagamentos instantâneos que delega a movimentação de saldo à Ledger API.
 * @ConfigurationPropertiesScan registra automaticamente todas as classes
 * anotadas com @ConfigurationProperties (ex.: [br.com.pixsimulado.config.LedgerProperties]).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class PixSimuladoApplication

fun main(args: Array<String>) {
    runApplication<PixSimuladoApplication>(*args)
}
