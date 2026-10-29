package br.com.pixsimulado.config

import br.com.pixsimulado.IntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Trava o contrato publicado em /v3/api-docs: rotas, onde a autenticação é exigida,
 * formato dos erros e cabeçalhos de idempotência. Se alguém remover uma anotação, a
 * documentação deixa de mentir em silêncio: este teste falha.
 */
class OpenApiContratoTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var json: ObjectMapper

    private lateinit var api: JsonNode

    @BeforeEach
    fun carregar() {
        api = json.readTree(rest.getForObject("/v3/api-docs", String::class.java))
    }

    @Test
    fun `publica todas as rotas das cinco etapas`() {
        val rotas = api["paths"].fieldNames().asSequence().toSet()
        val esperadas = setOf(
            "/api/v1/chaves", "/api/v1/chaves/{chave}",
            "/api/v1/qrcodes", "/api/v1/qrcodes/imagem", "/api/v1/qrcodes/leitura", "/api/v1/qrcodes/leitura/imagem",
            "/api/v1/pagamentos", "/api/v1/pagamentos/end-to-end-id", "/api/v1/pagamentos/{endToEndId}",
            "/api/v1/pagamentos/{endToEndId}/devolucoes", "/api/v1/pagamentos/{endToEndId}/devolucoes/id",
        )
        assertTrue(rotas.containsAll(esperadas), "faltando: ${esperadas - rotas}")
    }

    @Test
    fun `HTTP Basic e exigido onde ha dono, e so ai`() {
        assertEquals("basic", api["components"]["securitySchemes"][OpenApiConfig.BASIC]["scheme"].asText())
        assertFalse(api.has("security"), "não deve haver exigência global de autenticação")

        listOf(
            "post" to "/api/v1/pagamentos",
            "get" to "/api/v1/pagamentos/{endToEndId}",
            "post" to "/api/v1/pagamentos/{endToEndId}/devolucoes",
            "get" to "/api/v1/pagamentos/{endToEndId}/devolucoes",
            "post" to "/api/v1/chaves",
            "get" to "/api/v1/chaves",
            "delete" to "/api/v1/chaves/{chave}",
        ).forEach { (metodo, rota) -> assertTrue(exigeBasic(metodo, rota), "$metodo $rota deveria exigir basicAuth") }

        listOf(
            "get" to "/api/v1/chaves/{chave}",
            "post" to "/api/v1/qrcodes",
            "post" to "/api/v1/pagamentos/end-to-end-id",
        ).forEach { (metodo, rota) -> assertFalse(exigeBasic(metodo, rota), "$metodo $rota não deveria exigir basicAuth") }
    }

    @Test
    fun `erros sao documentados no formato RFC 9457 com codigo`() {
        val erro422 = api["paths"]["/api/v1/pagamentos"]["post"]["responses"]["422"]["content"]["application/problem+json"]
        assertEquals("#/components/schemas/ErroResponse", erro422["schema"]["\$ref"].asText())
        assertTrue(api["components"]["schemas"]["ErroResponse"]["properties"].has("codigo"))
    }

    @Test
    fun `respostas de pagamento e devolucao documentam o cabecalho de repeticao`() {
        listOf("/api/v1/pagamentos", "/api/v1/pagamentos/{endToEndId}/devolucoes").forEach { rota ->
            val cabecalhos = api["paths"][rota]["post"]["responses"]["201"]["headers"]
            assertTrue(cabecalhos.has("Idempotent-Replayed"), "$rota sem Idempotent-Replayed")
            assertTrue(cabecalhos.has("Location"), "$rota sem Location")
        }
    }

    private fun exigeBasic(metodo: String, rota: String): Boolean =
        api["paths"][rota][metodo]["security"]?.any { it.has(OpenApiConfig.BASIC) } ?: false
}
