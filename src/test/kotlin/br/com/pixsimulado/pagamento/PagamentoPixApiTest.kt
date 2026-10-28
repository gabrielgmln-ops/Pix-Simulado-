package br.com.pixsimulado.pagamento

import br.com.pixsimulado.IntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.BasicCredentials
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Testes de ponta a ponta dos pagamentos: HTTP -> serviço -> PostgreSQL real -> Ledger.
 *
 * A Ledger é simulada por um WireMock de verdade (servidor HTTP), e não por um mock do
 * RestClient: só assim dá para provocar um timeout de leitura real e provar que o
 * resultado incerto é reconciliado sem débito duplo. Dados todos sintéticos.
 */
class PagamentoPixApiTest : IntegrationTest() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var reconciliacao: ReconciliacaoPagamentos

    @BeforeEach
    fun preparar() {
        ledger.resetAll()
        jdbc.execute("TRUNCATE chave_pix, pagamento_pix")
        mvc.post("/api/v1/chaves") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"tipo": "EMAIL", "valor": "$CHAVE_LOJA", "contaId": "$CONTA_LOJA",
                 "titular": {"nome": "Loja Sintética Ltda", "cpf": "12345678909"}}
            """
        }.andExpect { status { isCreated() } }
    }

    // ===================== Caminho feliz =====================

    @Test
    fun `paga por chave e envia a Ledger o E2E como Idempotency-Key, com HTTP Basic`() {
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))
        val e2e = novoEndToEndId()

        pagar(e2e, chave = "LOJA.Sintetica@exemplo.com", valor = "25.90", descricao = "Pedido 123").andExpect {
            status { isCreated() }
            header { string("Location", "http://localhost/api/v1/pagamentos/$e2e") }
            header { string(PagamentoPixController.CABECALHO_REPETIDO, "false") }
            jsonPath("$.estado") { value("LIQUIDADO") }
            jsonPath("$.chaveDestino") { value(CHAVE_LOJA) }
            jsonPath("$.contaDestinoId") { value(CONTA_LOJA.toString()) }
            jsonPath("$.valor") { value(25.90) }
            jsonPath("$.ledgerTransacaoId") { value(TRANSACAO_ID.toString()) }
        }

        ledger.verify(
            exactly(1),
            postRequestedFor(urlEqualTo(ROTA_LEDGER))
                .withHeader("Idempotency-Key", equalTo(e2e))
                .withBasicAuth(BasicCredentials("pix-operador-teste", "senha-sintetica-de-teste"))
                .withRequestBody(
                    equalToJson(
                        """{"contaOrigemId": "$CONTA_CLIENTE", "contaDestinoId": "$CONTA_LOJA",
                            "valor": 25.90, "descricao": "Pedido 123"}""",
                    ),
                ),
        )
    }

    @Test
    fun `consulta o pagamento pelo E2E`() {
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))
        val e2e = novoEndToEndId()
        pagar(e2e, chave = CHAVE_LOJA, valor = "10")

        mvc.get("/api/v1/pagamentos/{e2e}", e2e).andExpect {
            status { isOk() }
            jsonPath("$.estado") { value("LIQUIDADO") }
            jsonPath("$.valor") { value(10.00) }
        }
    }

    @Test
    fun `consulta de pagamento inexistente devolve 404`() {
        mvc.get("/api/v1/pagamentos/{e2e}", "E99999999202610281530aBcDeFgHiJk").andExpect {
            status { isNotFound() }
            jsonPath("$.codigo") { value("PAGAMENTO_NAO_ENCONTRADO") }
        }
    }

    // ===================== Idempotência =====================

    @Test
    fun `repetir o mesmo pedido devolve o mesmo resultado sem nova chamada a Ledger`() {
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))
        val e2e = novoEndToEndId()
        pagar(e2e, chave = CHAVE_LOJA, valor = "10.00").andExpect { status { isCreated() } }

        // 10 e 10.00 são o mesmo valor; e-mail em maiúsculas é a mesma chave.
        pagar(e2e, chave = CHAVE_LOJA.uppercase(), valor = "10").andExpect {
            status { isCreated() }
            header { string(PagamentoPixController.CABECALHO_REPETIDO, "true") }
            jsonPath("$.ledgerTransacaoId") { value(TRANSACAO_ID.toString()) }
        }

        ledger.verify(exactly(1), postRequestedFor(urlEqualTo(ROTA_LEDGER)))
    }

    @Test
    fun `mesmo E2E com dados diferentes gera 422 e nao chama a Ledger de novo`() {
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))
        val e2e = novoEndToEndId()
        pagar(e2e, chave = CHAVE_LOJA, valor = "10.00")

        pagar(e2e, chave = CHAVE_LOJA, valor = "10.01").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("END_TO_END_ID_JA_UTILIZADO") }
        }
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo(ROTA_LEDGER)))
    }

    @Test
    fun `20 requisicoes simultaneas com o mesmo E2E geram uma unica transferencia`() {
        // Atraso na Ledger alarga a janela de corrida de propósito.
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID), atrasoMs = 300)
        val e2e = novoEndToEndId()

        val pool = Executors.newFixedThreadPool(20)
        val status = try {
            pool.invokeAll(List(20) { Callable { pagar(e2e, chave = CHAVE_LOJA, valor = "7.50").andReturn().response.status } })
                .map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(List(20) { 201 }, status)
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo(ROTA_LEDGER)))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pagamento_pix", Int::class.java))
    }

    // ===================== Recusa da Ledger =====================

    @Test
    fun `saldo insuficiente na Ledger vira 422 gravado e a repeticao devolve a mesma recusa`() {
        ledgerResponde(
            422,
            """{"type": "about:blank", "title": "Regra de negocio violada", "status": 422,
                "detail": "Saldo insuficiente na conta de origem"}""",
        )
        val e2e = novoEndToEndId()

        repeat(2) {
            pagar(e2e, chave = CHAVE_LOJA, valor = "1000.00").andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.codigo") { value("SALDO_INSUFICIENTE") }
            }
        }

        ledger.verify(exactly(1), postRequestedFor(urlEqualTo(ROTA_LEDGER)))
        mvc.get("/api/v1/pagamentos/{e2e}", e2e).andExpect {
            jsonPath("$.estado") { value("REJEITADO") }
            jsonPath("$.motivo.codigo") { value("SALDO_INSUFICIENTE") }
        }
    }

    @Test
    fun `conta inexistente na Ledger vira recusa CONTA_NAO_ENCONTRADA`() {
        ledgerResponde(404, """{"title": "Recurso nao encontrado", "status": 404, "detail": "Conta nao encontrada"}""")

        pagar(novoEndToEndId(), chave = CHAVE_LOJA, valor = "5").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("CONTA_NAO_ENCONTRADA") }
        }
    }

    // ===================== Resultado incerto e reconciliação =====================

    @Test
    fun `timeout da Ledger fica INCERTO e a reconciliacao liquida sem debitar duas vezes`() {
        // Primeira chamada: a Ledger demora mais que o read-timeout (800ms nos testes).
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID), atrasoMs = 1_500)
        val e2e = novoEndToEndId()

        pagar(e2e, chave = CHAVE_LOJA, valor = "42.00").andExpect {
            status { isAccepted() }
            jsonPath("$.estado") { value("INCERTO") }
            jsonPath("$.motivo.codigo") { value("RESULTADO_INCERTO") }
        }

        // Ainda dentro do intervalo de descanso: a reconciliação não toca no pagamento.
        assertEquals(0, reconciliacao.executar())

        // A Ledger volta ao normal; simulamos que o intervalo já passou.
        ledger.resetAll()
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))
        jdbc.update("UPDATE pagamento_pix SET atualizado_em = atualizado_em - interval '1 hour'")

        assertEquals(1, reconciliacao.executar())

        mvc.get("/api/v1/pagamentos/{e2e}", e2e).andExpect {
            jsonPath("$.estado") { value("LIQUIDADO") }
            jsonPath("$.ledgerTransacaoId") { value(TRANSACAO_ID.toString()) }
            jsonPath("$.motivo") { doesNotExist() }
        }
        // O reenvio usou a MESMA chave: na Ledger real, é a mesma transação devolvida.
        ledger.verify(exactly(1), postRequestedFor(urlEqualTo(ROTA_LEDGER)).withHeader("Idempotency-Key", equalTo(e2e)))
        assertEquals(2, jdbc.queryForObject("SELECT tentativas FROM pagamento_pix", Int::class.java))
    }

    @Test
    fun `erro 5xx fica INCERTO e a repeticao do cliente consulta a Ledger e liquida`() {
        ledgerResponde(503, """{"title": "Indisponivel", "status": 503}""")
        val e2e = novoEndToEndId()
        pagar(e2e, chave = CHAVE_LOJA, valor = "3.00").andExpect {
            status { isAccepted() }
            jsonPath("$.estado") { value("INCERTO") }
        }

        ledger.resetAll()
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))

        pagar(e2e, chave = CHAVE_LOJA, valor = "3.00").andExpect {
            status { isCreated() }
            header { string(PagamentoPixController.CABECALHO_REPETIDO, "true") }
            jsonPath("$.estado") { value("LIQUIDADO") }
        }
    }

    @Test
    fun `credencial recusada pela Ledger nao vira recusa definitiva`() {
        ledgerResponde(401, """{"title": "Nao autenticado", "status": 401}""")

        pagar(novoEndToEndId(), chave = CHAVE_LOJA, valor = "3.00").andExpect {
            status { isAccepted() }
            jsonPath("$.estado") { value("INCERTO") }
        }
    }

    // ===================== QR Code =====================

    @Test
    fun `paga por QR Code com valor fixo usando o valor do QR`() {
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))

        pagar(novoEndToEndId(), qrPayload = gerarQr(valor = "99.99")).andExpect {
            status { isCreated() }
            jsonPath("$.valor") { value(99.99) }
            jsonPath("$.chaveDestino") { value(CHAVE_LOJA) }
        }
    }

    @Test
    fun `QR Code com valor fixo recusa outro valor e aceita o mesmo valor`() {
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))
        val qr = gerarQr(valor = "99.99")

        pagar(novoEndToEndId(), qrPayload = qr, valor = "50.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("VALOR_QR_DIVERGENTE") }
        }
        // Três casas decimais são recusadas na entrada (400), mesmo que o último seja zero.
        pagar(novoEndToEndId(), qrPayload = qr, valor = "99.990").andExpect { status { isBadRequest() } }
        pagar(novoEndToEndId(), qrPayload = qr, valor = "99.99").andExpect { status { isCreated() } }
    }

    @Test
    fun `QR Code sem valor exige o valor no pedido`() {
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))
        val qr = gerarQr(valor = null)

        pagar(novoEndToEndId(), qrPayload = qr).andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("VALOR_OBRIGATORIO") }
        }
        pagar(novoEndToEndId(), qrPayload = qr, valor = "12.34").andExpect {
            status { isCreated() }
            jsonPath("$.valor") { value(12.34) }
        }
    }

    @Test
    fun `QR Code adulterado e recusado sem chamar a Ledger`() {
        val adulterado = gerarQr(valor = "99.99").replace("99.99", "09.99")

        pagar(novoEndToEndId(), qrPayload = adulterado).andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("QRCODE_CRC_INVALIDO") }
        }
        ledger.verify(exactly(0), postRequestedFor(urlEqualTo(ROTA_LEDGER)))
    }

    // ===================== Validações =====================

    @Test
    fun `informar chave e QR Code ao mesmo tempo gera 400`() {
        pagar(novoEndToEndId(), chave = CHAVE_LOJA, qrPayload = gerarQr(valor = "1.00"), valor = "1.00").andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("DESTINO_INVALIDO") }
        }
    }

    @Test
    fun `sem destino gera 400`() {
        pagar(novoEndToEndId(), valor = "1.00").andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("DESTINO_INVALIDO") }
        }
    }

    @Test
    fun `pagar para a propria conta gera 422`() {
        pagar(novoEndToEndId(), chave = CHAVE_LOJA, valor = "1.00", contaOrigem = CONTA_LOJA).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("MESMA_CONTA") }
        }
    }

    @Test
    fun `chave de destino inexistente gera 404`() {
        pagar(novoEndToEndId(), chave = "ninguem@exemplo.com", valor = "1.00").andExpect {
            status { isNotFound() }
            jsonPath("$.codigo") { value("CHAVE_NAO_ENCONTRADA") }
        }
    }

    @Test
    fun `valor com fracao de centavo, zero ou negativo gera 400`() {
        listOf("10.001", "0", "-5.00").forEach { valor ->
            pagar(novoEndToEndId(), chave = CHAVE_LOJA, valor = valor).andExpect {
                status { isBadRequest() }
                jsonPath("$.erros[0].campo") { value("valor") }
            }
        }
    }

    @Test
    fun `E2E mal formado gera 400`() {
        pagar("E123", chave = CHAVE_LOJA, valor = "1.00").andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("END_TO_END_ID_INVALIDO") }
        }
    }

    @Test
    fun `E2E de outra instituicao gera 422`() {
        pagar("E12345678202610281530aBcDeFgHiJk", chave = CHAVE_LOJA, valor = "1.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("END_TO_END_ID_DE_OUTRA_INSTITUICAO") }
        }
    }

    @Test
    fun `E2E com mais de 24 horas gera 422`() {
        pagar("E99999999202001010000aBcDeFgHiJk", chave = CHAVE_LOJA, valor = "1.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("END_TO_END_ID_EXPIRADO") }
        }
    }

    @Test
    fun `conta de origem que nao e UUID gera 400 no campo`() {
        mvc.post("/api/v1/pagamentos") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"endToEndId": "${novoEndToEndId()}", "contaOrigemId": "conta-1", "chave": "$CHAVE_LOJA", "valor": 1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.erros[0].campo") { value("contaOrigemId") }
        }
    }

    // ===================== Apoio =====================

    private fun novoEndToEndId(): String {
        val corpo = mvc.post("/api/v1/pagamentos/end-to-end-id")
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return json.readTree(corpo)["endToEndId"].asText()
    }

    private fun pagar(
        e2e: String,
        chave: String? = null,
        qrPayload: String? = null,
        valor: String? = null,
        descricao: String? = null,
        contaOrigem: UUID = CONTA_CLIENTE,
    ): ResultActionsDsl = mvc.post("/api/v1/pagamentos") {
        contentType = MediaType.APPLICATION_JSON
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

    private fun gerarQr(valor: String?): String {
        val campoValor = valor?.let { """, "valor": $it""" } ?: ""
        val corpo = mvc.post("/api/v1/qrcodes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"chave": "$CHAVE_LOJA"$campoValor}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readTree(corpo)["payload"].asText()
    }

    private fun ledgerResponde(status: Int, corpo: String, atrasoMs: Int = 0) {
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

    private fun transacaoLedger(id: UUID) = """
        {"id": "$id", "tipo": "TRANSFERENCIA", "contaOrigemId": "$CONTA_CLIENTE",
         "contaDestinoId": "$CONTA_LOJA", "valor": 1.00, "criadaEm": "2026-10-28T15:30:00Z"}
    """

    companion object {
        const val ROTA_LEDGER = "/api/v1/transferencias"
        const val CHAVE_LOJA = "loja.sintetica@exemplo.com"
        val CONTA_LOJA: UUID = UUID.fromString("0b8f4c2e-1d3a-4e5f-9a7b-6c5d4e3f2a10")
        val CONTA_CLIENTE: UUID = UUID.fromString("7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f")
        val TRANSACAO_ID: UUID = UUID.fromString("c0ffee00-1111-4222-8333-444455556666")

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
