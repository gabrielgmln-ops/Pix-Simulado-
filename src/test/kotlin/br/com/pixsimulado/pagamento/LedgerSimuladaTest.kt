package br.com.pixsimulado.pagamento

import br.com.pixsimulado.IntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.BasicCredentials
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Base dos testes de pagamento e devolução: PostgreSQL real (Testcontainers) e a
 * Ledger simulada por um WireMock de verdade (servidor HTTP), não por um mock do
 * RestClient. Só assim dá para provocar um timeout de leitura real e provar que um
 * resultado incerto é reconciliado sem débito duplo. Dados todos sintéticos.
 *
 * Titulares: CONTA_CLIENTE pertence ao cliente (pagador) e CONTA_LOJA à loja
 * (recebedora, dona da chave CHAVE_LOJA).
 */
abstract class LedgerSimuladaTest : IntegrationTest() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var reconciliacao: ReconciliacaoPagamentos

    @BeforeEach
    fun preparar() {
        reiniciarLedger()
        jdbc.execute("TRUNCATE chave_pix, pagamento_pix, devolucao_pix")
        mvc.post("/api/v1/chaves") {
            contentType = MediaType.APPLICATION_JSON
            header(HttpHeaders.AUTHORIZATION, CRED_LOJA)
            content = """
                {"tipo": "EMAIL", "valor": "$CHAVE_LOJA", "contaId": "$CONTA_LOJA",
                 "titular": {"nome": "Loja Sintética Ltda", "cpf": "12345678909"}}
            """
        }.andExpect { status { isCreated() } }
    }


    protected fun novoEndToEndId(): String {
        val corpo = mvc.post("/api/v1/pagamentos/end-to-end-id")
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return json.readTree(corpo)["endToEndId"].asText()
    }

    protected fun pagar(
        e2e: String,
        chave: String? = null,
        qrPayload: String? = null,
        valor: String? = null,
        descricao: String? = null,
        contaOrigem: UUID = CONTA_CLIENTE,
        credencial: String? = CRED_CLIENTE,
    ): ResultActionsDsl = mvc.post("/api/v1/pagamentos") {
        contentType = MediaType.APPLICATION_JSON
        credencial?.let { header(HttpHeaders.AUTHORIZATION, it) }
        content = json.writeValueAsString(
            buildMap {
                put("endToEndId", e2e)
                put("contaOrigemId", contaOrigem.toString())
                chave?.let { put("chave", it) }
                qrPayload?.let { put("qrPayload", it) }
                valor?.let { put("valor", it.toBigDecimal()) }
                descricao?.let { put("descricao", it) }
            },
        )
    }

    protected fun gerarQr(valor: String?): String {
        val campoValor = valor?.let { """, "valor": $it""" } ?: ""
        val corpo = mvc.post("/api/v1/qrcodes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"chave": "$CHAVE_LOJA"$campoValor}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readTree(corpo)["payload"].asText()
    }

    /** Zera a Ledger simulada e recria as contas: cada titular só enxerga a própria. */
    protected fun reiniciarLedger() {
        ledger.resetAll()
        ledger.stubFor(
            get(urlEqualTo("/api/v1/contas/$CONTA_CLIENTE")).atPriority(1)
                .withBasicAuth(USUARIO_CLIENTE, SENHA_CLIENTE)
                .willReturn(okJson("""{"id": "$CONTA_CLIENTE"}""")),
        )
        ledger.stubFor(
            get(urlEqualTo("/api/v1/contas/$CONTA_LOJA")).atPriority(1)
                .withBasicAuth(USUARIO_LOJA, SENHA_LOJA)
                .willReturn(okJson("""{"id": "$CONTA_LOJA"}""")),
        )
        ledger.stubFor(
            get(urlPathMatching("/api/v1/contas/.*")).atPriority(2)
                .withBasicAuth(USUARIO_CLIENTE, "senha-errada")
                .willReturn(aResponse().withStatus(401)),
        )
        // Qualquer outra combinação: conta alheia, e a Ledger responde 404 (anti-enumeração).
        ledger.stubFor(get(urlPathMatching("/api/v1/contas/.*")).atPriority(10).willReturn(aResponse().withStatus(404)))
    }

    protected fun ledgerResponde(status: Int, corpo: String, atrasoMs: Int = 0) {
        ledger.stubFor(
            post(urlEqualTo(ROTA_LEDGER)).willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(corpo)
                    .withFixedDelay(atrasoMs),
            ),
        )
    }

    protected fun transacaoLedger(id: UUID) = """
        {"id": "$id", "tipo": "TRANSFERENCIA", "contaOrigemId": "$CONTA_CLIENTE",
         "contaDestinoId": "$CONTA_LOJA", "valor": 1.00, "criadaEm": "2026-10-28T15:30:00Z"}
    """

    companion object {
        const val ROTA_LEDGER = "/api/v1/transferencias"
        const val CHAVE_LOJA = "loja.sintetica@exemplo.com"
        val CONTA_LOJA: UUID = UUID.fromString("0b8f4c2e-1d3a-4e5f-9a7b-6c5d4e3f2a10")
        val CONTA_CLIENTE: UUID = UUID.fromString("7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f")
        val TRANSACAO_ID: UUID = UUID.fromString("c0ffee00-1111-4222-8333-444455556666")

        // Titulares sintéticos na Ledger: o cliente é dono de CONTA_CLIENTE; a loja, de CONTA_LOJA.
        const val USUARIO_CLIENTE = "cliente-sintetico"
        const val SENHA_CLIENTE = "senha-cliente"
        const val USUARIO_LOJA = "loja-sintetica"
        const val SENHA_LOJA = "senha-loja"
        val CRED_CLIENTE = basic(USUARIO_CLIENTE, SENHA_CLIENTE)
        val CRED_LOJA = basic(USUARIO_LOJA, SENHA_LOJA)
        val CRED_INTRUSO = basic("intruso", "qualquer")

        fun basic(usuario: String, senha: String) =
            "Basic " + Base64.getEncoder().encodeToString("$usuario:$senha".toByteArray())

        /** Mesma porta do application-test.yml (pix.ledger.base-url). */
        val ledger = WireMockServer(options().port(18089))

        @JvmStatic
        @BeforeAll
        fun subirLedger() = ledger.start()

        @JvmStatic
        @AfterAll
        fun derrubarLedger() = ledger.stop()
    }
}
