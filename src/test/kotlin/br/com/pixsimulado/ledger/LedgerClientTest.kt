package br.com.pixsimulado.ledger

import br.com.pixsimulado.config.LedgerProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Teste unitário da classificação das respostas da Ledger (sem Spring, sem banco).
 *
 * É a peça que decide se um pagamento foi recusado com certeza (REJEITADO) ou se o
 * resultado é desconhecido (INCERTO). Confundir as duas é o erro mais caro possível:
 * tratar um "talvez debitou" como recusa permitiria o cliente pagar de novo.
 */
class LedgerClientTest {

    private val cliente = LedgerClient(
        LedgerProperties(
            baseUrl = "http://localhost:${servidor.port()}",
            usuario = "pix-operador",
            senha = "senha-sintetica",
            readTimeout = Duration.ofMillis(500),
        ),
        ObjectMapper(),
        RestClient.builder(),
    )

    private val pedido = TransferenciaLedger(UUID.randomUUID(), UUID.randomUUID(), BigDecimal("10.00"), null)

    @BeforeEach
    fun limpar() = servidor.resetAll()

    // ===================== Transferência =====================

    @Test
    fun `2xx com id vira Efetivada`() {
        val id = UUID.randomUUID()
        transferenciaResponde(201, """{"id": "$id"}""")

        assertEquals(ResultadoLedger.Efetivada(id), cliente.transferir("E1", pedido))
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            "422 | {\"title\": \"Regra de negocio violada\", \"detail\": \"Saldo insuficiente na conta de origem\"} | SALDO_INSUFICIENTE",
            "422 | {\"title\": \"Regra de negocio violada\", \"detail\": \"Conta encerrada\"}                     | LEDGER_RECUSOU",
            "422 | {}                                                                                           | LEDGER_RECUSOU",
            "404 | {\"title\": \"Recurso nao encontrado\"}                                                     | CONTA_NAO_ENCONTRADA",
            "403 | {\"title\": \"Acesso negado\"}                                                             | LEDGER_ACESSO_NEGADO",
            "400 | {\"title\": \"Dados invalidos\", \"detail\": \"valor invalido\"}                           | LEDGER_DADOS_INVALIDOS",
            "400 | nao-e-json                                                                                   | LEDGER_DADOS_INVALIDOS",
        ],
    )
    fun `4xx de negocio vira Recusada com codigo estavel`(status: Int, corpo: String, codigo: String) {
        transferenciaResponde(status, corpo)

        val resultado = assertIs<ResultadoLedger.Recusada>(cliente.transferir("E1", pedido))
        assertEquals(codigo, resultado.codigo)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "201 | {\"tipo\": \"TRANSFERENCIA\"}",                                 // 2xx sem id
            "200 | {\"id\": \"nao-e-uuid\"}",                                      // 2xx com id ilegível
            "422 | {\"title\": \"Conflito de idempotencia\", \"detail\": \"x\"}",  // chave usada com outro corpo
            "401 | {\"title\": \"Nao autenticado\"}",
            "500 | {\"title\": \"Erro interno\"}",
            "503 | ''",
        ],
    )
    fun `respostas sem certeza viram Indeterminada`(status: Int, corpo: String) {
        transferenciaResponde(status, corpo)

        assertIs<ResultadoLedger.Indeterminada>(cliente.transferir("E1", pedido))
    }

    @Test
    fun `timeout de leitura vira Indeterminada`() {
        servidor.stubFor(post(urlEqualTo("/api/v1/transferencias")).willReturn(aResponse().withStatus(201).withFixedDelay(1_500)))

        assertIs<ResultadoLedger.Indeterminada>(cliente.transferir("E1", pedido))
    }

    @Test
    fun `Ledger desligada vira Indeterminada`() {
        val desligado = LedgerClient(
            LedgerProperties(baseUrl = "http://localhost:1", usuario = "u", senha = "s"),
            ObjectMapper(),
            RestClient.builder(),
        )

        assertIs<ResultadoLedger.Indeterminada>(desligado.transferir("E1", pedido))
        assertEquals(AcessoConta.INDISPONIVEL, desligado.verificarAcesso("Basic eDp5", UUID.randomUUID()))
    }

    // ===================== Verificação de acesso =====================

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "200, PERMITIDO",
        "401, NAO_AUTENTICADO",
        "403, NEGADO",
        "404, NEGADO",
        "500, INDISPONIVEL",
    )
    fun `verificacao de acesso traduz o status da Ledger`(status: Int, esperado: AcessoConta) {
        val conta = UUID.randomUUID()
        servidor.stubFor(get(urlEqualTo("/api/v1/contas/$conta")).willReturn(aResponse().withStatus(status)))

        assertEquals(esperado, cliente.verificarAcesso("Basic Y2xpZW50ZTpzZW5oYQ==", conta))
    }

    @Test
    fun `verificacao de acesso usa a credencial do cliente, nao a do operador`() {
        val conta = UUID.randomUUID()
        servidor.stubFor(get(urlEqualTo("/api/v1/contas/$conta")).willReturn(aResponse().withStatus(200)))

        cliente.verificarAcesso("Basic Y2xpZW50ZTpzZW5oYQ==", conta)

        servidor.verify(
            getRequestedFor(urlEqualTo("/api/v1/contas/$conta"))
                .withHeader("Authorization", equalTo("Basic Y2xpZW50ZTpzZW5oYQ==")),
        )
    }

    private fun transferenciaResponde(status: Int, corpo: String) {
        servidor.stubFor(
            post(urlEqualTo("/api/v1/transferencias"))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(corpo)),
        )
    }

    companion object {
        // Porta dinâmica: não disputa a 18089 com os testes de integração.
        private val servidor = WireMockServer(options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun subir() = servidor.start()

        @JvmStatic
        @AfterAll
        fun derrubar() = servidor.stop()
    }
}
