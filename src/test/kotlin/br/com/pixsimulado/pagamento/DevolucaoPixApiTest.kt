package br.com.pixsimulado.pagamento

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.exactly
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Testes de ponta a ponta das devoluções (Etapa 4): lançamento inverso na Ledger,
 * limites, prazo, idempotência, concorrência e controle de acesso.
 * Cenário: o cliente pagou R$ 100,00 à loja; quem devolve é a loja.
 */
class DevolucaoPixApiTest : LedgerSimuladaTest() {

    // ===================== Caminho feliz =====================

    @Test
    fun `devolucao total e um lancamento inverso na Ledger e preserva o pagamento original`() {
        val e2e = pagamentoLiquidado("100.00")
        val id = novoIdDevolucao(e2e)

        devolver(e2e, id, valor = "100.00").andExpect {
            status { isCreated() }
            header { string("Location", "http://localhost/api/v1/pagamentos/$e2e/devolucoes/$id") }
            header { string(PagamentoPixController.CABECALHO_REPETIDO, "false") }
            jsonPath("$.estado") { value("LIQUIDADO") }
            jsonPath("$.motivo") { value("MD06") }
            jsonPath("$.contaOrigemId") { value(CONTA_LOJA.toString()) }
            jsonPath("$.contaDestinoId") { value(CONTA_CLIENTE.toString()) }
            jsonPath("$.ledgerTransacaoId") { value(DEVOLUCAO_LEDGER_ID.toString()) }
        }

        // Sentido inverso, com o id da devolução (prefixo D) como Idempotency-Key.
        ledger.verify(
            exactly(1),
            postRequestedFor(urlEqualTo(ROTA_LEDGER))
                .withHeader("Idempotency-Key", equalTo(id))
                .withRequestBody(
                    equalToJson("""{"contaOrigemId": "$CONTA_LOJA", "contaDestinoId": "$CONTA_CLIENTE", "valor": 100.00}""", true, true),
                ),
        )

        // O pagamento original continua igual; o devolvido é calculado pela soma.
        mvc.get("/api/v1/pagamentos/{e2e}", e2e) { header(HttpHeaders.AUTHORIZATION, CRED_CLIENTE) }.andExpect {
            jsonPath("$.estado") { value("LIQUIDADO") }
            jsonPath("$.valor") { value(100.00) }
            jsonPath("$.valorDevolvido") { value(100.00) }
            jsonPath("$.ledgerTransacaoId") { value(TRANSACAO_ID.toString()) }
        }
    }

    @Test
    fun `devolucoes parciais somam ate o valor pago e nao passam dele`() {
        val e2e = pagamentoLiquidado("100.00")

        devolver(e2e, novoIdDevolucao(e2e), valor = "30.00").andExpect { status { isCreated() } }
        devolver(e2e, novoIdDevolucao(e2e), valor = "70.00").andExpect { status { isCreated() } }
        devolver(e2e, novoIdDevolucao(e2e), valor = "0.01").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("VALOR_DEVOLUCAO_EXCEDIDO") }
        }

        listar(e2e).andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(2))
            jsonPath("$[0].valor") { value(30.00) }
        }
    }

    @Test
    fun `devolucao acima do saldo disponivel informa quanto resta`() {
        val e2e = pagamentoLiquidado("50.00")
        devolver(e2e, novoIdDevolucao(e2e), valor = "20.00")

        devolver(e2e, novoIdDevolucao(e2e), valor = "30.01").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.detail") { value("Valor acima do disponível para devolução (disponível: 30.00)") }
        }
    }

    @Test
    fun `20 devolucoes simultaneas de 10 reais num pagamento de 100 liquidam exatamente 10`() {
        val e2e = pagamentoLiquidado("100.00")
        val ids = List(20) { novoIdDevolucao(e2e) }

        val pool = Executors.newFixedThreadPool(20)
        val status = try {
            pool.invokeAll(ids.map { id -> Callable { devolver(e2e, id, valor = "10.00").andReturn().response.status } })
                .map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(10, status.count { it == 201 }, "status: $status")
        assertEquals(10, status.count { it == 422 }, "status: $status")
        val devolvido = jdbc.queryForObject(
            "SELECT sum(valor) FROM devolucao_pix WHERE estado = 'LIQUIDADO'", BigDecimal::class.java,
        )
        assertEquals(0, BigDecimal("100.00").compareTo(devolvido))
    }

    // ===================== Idempotência =====================

    @Test
    fun `repetir a devolucao devolve o mesmo resultado sem nova chamada a Ledger`() {
        val e2e = pagamentoLiquidado("100.00")
        val id = novoIdDevolucao(e2e)
        devolver(e2e, id, valor = "10.00").andExpect { status { isCreated() } }

        devolver(e2e, id, valor = "10").andExpect {
            status { isCreated() }
            header { string(PagamentoPixController.CABECALHO_REPETIDO, "true") }
        }

        ledger.verify(exactly(1), postRequestedFor(urlEqualTo(ROTA_LEDGER)).withHeader("Idempotency-Key", equalTo(id)))
    }

    @Test
    fun `mesmo id de devolucao com dados diferentes gera 422`() {
        val e2e = pagamentoLiquidado("100.00")
        val id = novoIdDevolucao(e2e)
        devolver(e2e, id, valor = "10.00")

        devolver(e2e, id, valor = "11.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("ID_DEVOLUCAO_JA_UTILIZADO") }
        }
    }

    @Test
    fun `mesmo id de devolucao em outro pagamento gera 422`() {
        val primeiro = pagamentoLiquidado("100.00")
        val segundo = pagamentoLiquidado("100.00")
        val id = novoIdDevolucao(primeiro)
        devolver(primeiro, id, valor = "10.00")

        devolver(segundo, id, valor = "10.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("ID_DEVOLUCAO_JA_UTILIZADO") }
        }
    }

    // ===================== Regras =====================

    @Test
    fun `pagamento recusado nao pode ser devolvido`() {
        ledgerResponde(422, """{"title": "Regra de negocio violada", "status": 422, "detail": "Saldo insuficiente na conta de origem"}""")
        val e2e = novoEndToEndId()
        pagar(e2e, chave = CHAVE_LOJA, valor = "100.00").andExpect { status { isUnprocessableEntity() } }

        devolver(e2e, novoIdDevolucao(e2e), valor = "10.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("PAGAMENTO_NAO_LIQUIDADO") }
        }
    }

    @Test
    fun `devolucao depois de 90 dias da liquidacao gera 422`() {
        val e2e = pagamentoLiquidado("100.00")
        jdbc.update("UPDATE pagamento_pix SET atualizado_em = now() - interval '91 days'")

        devolver(e2e, novoIdDevolucao(e2e), valor = "10.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("PRAZO_DEVOLUCAO_EXPIRADO") }
        }
    }

    @Test
    fun `saldo insuficiente do recebedor rejeita a devolucao, que nao conta no limite`() {
        val e2e = pagamentoLiquidado("100.00")
        devolucaoNaLedger(422, """{"title": "Regra de negocio violada", "status": 422, "detail": "Saldo insuficiente na conta de origem"}""")

        devolver(e2e, novoIdDevolucao(e2e), valor = "100.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("SALDO_INSUFICIENTE") }
        }

        // A loja recebe saldo; a nova devolução do valor total é aceita.
        devolucaoNaLedger(201, transacaoLedger(DEVOLUCAO_LEDGER_ID))
        devolver(e2e, novoIdDevolucao(e2e), valor = "100.00").andExpect { status { isCreated() } }
    }

    @Test
    fun `timeout fica INCERTO, conta no limite e a reconciliacao liquida`() {
        val e2e = pagamentoLiquidado("100.00")
        devolucaoNaLedger(201, transacaoLedger(DEVOLUCAO_LEDGER_ID), atrasoMs = 1_500)
        val id = novoIdDevolucao(e2e)

        devolver(e2e, id, valor = "100.00").andExpect {
            status { isAccepted() }
            jsonPath("$.estado") { value("INCERTO") }
        }

        // Pode ter devolvido: o valor fica comprometido e uma segunda devolução é barrada.
        devolver(e2e, novoIdDevolucao(e2e), valor = "1.00").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.codigo") { value("VALOR_DEVOLUCAO_EXCEDIDO") }
        }

        devolucaoNaLedger(201, transacaoLedger(DEVOLUCAO_LEDGER_ID))
        jdbc.update("UPDATE devolucao_pix SET atualizado_em = atualizado_em - interval '1 hour'")
        assertEquals(1, reconciliacao.executar())

        listar(e2e).andExpect {
            jsonPath("$[0].estado") { value("LIQUIDADO") }
            jsonPath("$[0].falha") { doesNotExist() }
        }
    }

    // ===================== Controle de acesso =====================

    @Test
    fun `o pagador nao pode devolver o proprio pagamento`() {
        val e2e = pagamentoLiquidado("100.00")

        devolver(e2e, novoIdDevolucao(e2e), valor = "10.00", credencial = CRED_CLIENTE).andExpect {
            status { isForbidden() }
            jsonPath("$.codigo") { value("ACESSO_NEGADO") }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM devolucao_pix", Int::class.java))
    }

    @Test
    fun `terceiro nao enxerga nem devolve pagamento alheio`() {
        val e2e = pagamentoLiquidado("100.00")

        devolver(e2e, novoIdDevolucao(e2e), valor = "10.00", credencial = CRED_INTRUSO).andExpect {
            status { isNotFound() }
            jsonPath("$.codigo") { value("PAGAMENTO_NAO_ENCONTRADO") }
        }
        listar(e2e, CRED_INTRUSO).andExpect { status { isNotFound() } }
    }

    @Test
    fun `sem credencial a devolucao e recusada com 401`() {
        val e2e = pagamentoLiquidado("100.00")
        devolver(e2e, novoIdDevolucao(e2e), valor = "10.00", credencial = null).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `pagador e recebedor listam as devolucoes`() {
        val e2e = pagamentoLiquidado("100.00")
        devolver(e2e, novoIdDevolucao(e2e), valor = "10.00")

        listOf(CRED_CLIENTE, CRED_LOJA).forEach { credencial ->
            listar(e2e, credencial).andExpect {
                status { isOk() }
                jsonPath("$", hasSize<Any>(1))
            }
        }
    }

    // ===================== Validações =====================

    @Test
    fun `identificador com prefixo de pagamento gera 400`() {
        val e2e = pagamentoLiquidado("100.00")
        devolver(e2e, e2e, valor = "10.00").andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("ID_DEVOLUCAO_INVALIDO") }
        }
    }

    @Test
    fun `valor com fracao de centavo ou motivo desconhecido gera 400`() {
        val e2e = pagamentoLiquidado("100.00")
        devolver(e2e, novoIdDevolucao(e2e), valor = "10.001").andExpect { status { isBadRequest() } }
        devolver(e2e, novoIdDevolucao(e2e), valor = "10.00", motivo = "XX99").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `devolucao de pagamento inexistente gera 404`() {
        devolver("E99999999202610281530aBcDeFgHiJk", "D99999999202610281530aBcDeFgHiJk", valor = "1.00").andExpect {
            status { isNotFound() }
        }
    }

    // ===================== Apoio =====================

    /** Cria um pagamento cliente -> loja já liquidado na Ledger simulada. */
    private fun pagamentoLiquidado(valor: String): String {
        ledgerResponde(201, transacaoLedger(TRANSACAO_ID))
        devolucaoNaLedger(201, transacaoLedger(DEVOLUCAO_LEDGER_ID))
        val e2e = novoEndToEndId()
        pagar(e2e, chave = CHAVE_LOJA, valor = valor).andExpect { status { isCreated() } }
        return e2e
    }

    /** Stub só para devoluções (Idempotency-Key com prefixo D), acima do stub de pagamentos. */
    private fun devolucaoNaLedger(status: Int, corpo: String, atrasoMs: Int = 0) {
        ledger.stubFor(
            post(urlEqualTo(ROTA_LEDGER)).atPriority(1)
                .withHeader("Idempotency-Key", matching("D.*"))
                .willReturn(
                    aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody(corpo).withFixedDelay(atrasoMs),
                ),
        )
    }

    private fun novoIdDevolucao(e2e: String): String {
        val corpo = mvc.post("/api/v1/pagamentos/{e2e}/devolucoes/id", e2e)
            .andExpect { status { isCreated() } }
            .andReturn().response.contentAsString
        return json.readTree(corpo)["idDevolucao"].asText()
    }

    private fun devolver(
        e2e: String,
        id: String,
        valor: String,
        motivo: String = "MD06",
        credencial: String? = CRED_LOJA,
    ): ResultActionsDsl = mvc.post("/api/v1/pagamentos/{e2e}/devolucoes", e2e) {
        contentType = MediaType.APPLICATION_JSON
        credencial?.let { header(HttpHeaders.AUTHORIZATION, it) }
        content = """{"idDevolucao": "$id", "valor": $valor, "motivo": "$motivo"}"""
    }

    private fun listar(e2e: String, credencial: String = CRED_LOJA): ResultActionsDsl =
        mvc.get("/api/v1/pagamentos/{e2e}/devolucoes", e2e) { header(HttpHeaders.AUTHORIZATION, credencial) }

    private companion object {
        val DEVOLUCAO_LEDGER_ID: UUID = UUID.fromString("d0d0d0d0-2222-4333-8444-555566667777")
    }
}
