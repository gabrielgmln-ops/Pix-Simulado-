package br.com.pixsimulado.chave

import br.com.pixsimulado.pagamento.LedgerSimuladaTest
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import com.github.tomakehurst.wiremock.client.WireMock
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * Testes de ponta a ponta da API de chaves: HTTP -> serviço -> PostgreSQL real.
 * Todos os dados são sintéticos.
 */
class ChavePixApiTest : LedgerSimuladaTest() {

    @BeforeEach
    fun limparBanco() {
        jdbc.execute("TRUNCATE chave_pix")
        // Nesta classe, o titular de teste é dono de qualquer conta na Ledger simulada.
        ledger.stubFor(
            WireMock.get(WireMock.urlPathMatching("/api/v1/contas/.*")).atPriority(1)
                .withBasicAuth(TITULAR, SENHA_TITULAR)
                .willReturn(WireMock.aResponse().withStatus(200)),
        )
    }

    // ===================== Cadastro =====================

    @Test
    fun `cadastra chave CPF com pontuacao e devolve valor normalizado e CPF do titular mascarado`() {
        cadastrar(tipo = "CPF", valor = "123.456.789-09", cpfTitular = CPF_A)
            .andExpect {
                status { isCreated() }
                header { string("Location", "http://localhost/api/v1/chaves/12345678909") }
                jsonPath("$.tipo") { value("CPF") }
                jsonPath("$.valor") { value("12345678909") }
                jsonPath("$.titular.cpf") { value("***.456.789-**") }
            }
    }

    @Test
    fun `cadastra chave EMAIL normalizada em minusculas`() {
        cadastrar(tipo = "EMAIL", valor = "Fulano.Sintetico@Exemplo.com")
            .andExpect {
                status { isCreated() }
                jsonPath("$.valor") { value("fulano.sintetico@exemplo.com") }
            }
    }

    @Test
    fun `cadastra chave ALEATORIA gerando UUID v4 no servidor`() {
        cadastrar(tipo = "ALEATORIA", valor = null)
            .andExpect {
                status { isCreated() }
                jsonPath("$.valor") { value(matchesPattern("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")) }
            }
    }

    @Test
    fun `rejeita chave ALEATORIA com valor escolhido pelo cliente`() {
        cadastrar(tipo = "ALEATORIA", valor = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b")
            .andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.codigo") { value("VALOR_NAO_PERMITIDO") }
            }
    }

    @Test
    fun `rejeita CPF com digito verificador invalido`() {
        cadastrar(tipo = "CPF", valor = "123.456.789-00", cpfTitular = "123.456.789-00")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.codigo") { value("CPF_TITULAR_INVALIDO") }
            }
    }

    @Test
    fun `rejeita EMAIL invalido`() {
        cadastrar(tipo = "EMAIL", valor = "fulano@sem-tld")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.codigo") { value("CHAVE_INVALIDA") }
            }
    }

    @Test
    fun `rejeita chave CPF diferente do CPF do titular`() {
        cadastrar(tipo = "CPF", valor = CPF_B, cpfTitular = CPF_A)
            .andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.codigo") { value("CPF_DIVERGENTE_DO_TITULAR") }
            }
    }

    @Test
    fun `rejeita chave ja cadastrada mesmo digitada de outra forma, sem revelar o dono`() {
        cadastrar(tipo = "EMAIL", valor = "dono@exemplo.com", contaId = "conta-a", cpfTitular = CPF_A)
            .andExpect { status { isCreated() } }

        cadastrar(tipo = "EMAIL", valor = "DONO@EXEMPLO.COM", contaId = "conta-b", cpfTitular = CPF_B)
            .andExpect {
                status { isConflict() }
                jsonPath("$.codigo") { value("CHAVE_JA_CADASTRADA") }
                jsonPath("$.detail") { value("Chave já cadastrada") }
            }
    }

    @Test
    fun `rejeita a sexta chave ativa da mesma conta`() {
        repeat(5) { cadastrar(tipo = "ALEATORIA", valor = null).andExpect { status { isCreated() } } }

        cadastrar(tipo = "ALEATORIA", valor = null)
            .andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.codigo") { value("LIMITE_CHAVES_EXCEDIDO") }
            }
    }

    @Test
    fun `cadastros concorrentes na mesma conta nao furam o limite`() {
        // 10 requisições simultâneas contra uma conta vazia: exatamente 5 devem passar.
        val pool = Executors.newFixedThreadPool(10)
        val status = try {
            pool.invokeAll(List(10) { Callable { cadastrar(tipo = "ALEATORIA", valor = null).andReturn().response.status } })
                .map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(5, status.count { it == 201 }, "status recebidos: $status")
        assertEquals(5, status.count { it == 422 }, "status recebidos: $status")
    }

    @Test
    fun `rejeita conta ja vinculada a outro titular`() {
        cadastrar(tipo = "ALEATORIA", valor = null, contaId = "conta-x", cpfTitular = CPF_A)
            .andExpect { status { isCreated() } }

        cadastrar(tipo = "ALEATORIA", valor = null, contaId = "conta-x", cpfTitular = CPF_B)
            .andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.codigo") { value("CONTA_DE_OUTRO_TITULAR") }
            }
    }

    @Test
    fun `campos obrigatorios ausentes geram 400 com lista de erros por campo`() {
        mvc.post("/api/v1/chaves") {
            contentType = MediaType.APPLICATION_JSON
            header(HttpHeaders.AUTHORIZATION, CRED_TITULAR)
            content = """{"titular": {}}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("CAMPOS_INVALIDOS") }
            jsonPath("$.erros[*].campo") { value(org.hamcrest.Matchers.containsInAnyOrder("contaId", "tipo", "titular.cpf", "titular.nome")) }
        }
    }

    @Test
    fun `contaId que nao e UUID gera 400 no campo contaId`() {
        mvc.post("/api/v1/chaves") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"tipo": "ALEATORIA", "contaId": "conta-0001", "titular": {"nome": "Fulano", "cpf": "$CPF_A"}}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.erros[0].campo") { value("contaId") }
        }
    }

    @Test
    fun `listagem com contaId que nao e UUID gera 400`() {
        mvc.get("/api/v1/chaves") { param("contaId", "conta-l"); header(HttpHeaders.AUTHORIZATION, CRED_TITULAR) }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `tipo de chave desconhecido gera 400`() {
        cadastrar(tipo = "TELEFONE", valor = "+5511999999999")
            .andExpect { status { isBadRequest() } }
    }

    // ===================== Consulta =====================

    @Test
    fun `consulta chave por qualquer formato equivalente`() {
        cadastrar(tipo = "CPF", valor = CPF_A, cpfTitular = CPF_A)

        listOf("12345678909", "123.456.789-09").forEach { formato ->
            mvc.get("/api/v1/chaves/{chave}", formato).andExpect {
                status { isOk() }
                jsonPath("$.valor") { value("12345678909") }
                jsonPath("$.contaId") { value(conta("conta-0001").toString()) }
            }
        }
    }

    @Test
    fun `consulta chave EMAIL ignorando maiusculas`() {
        cadastrar(tipo = "EMAIL", valor = "fulano@exemplo.com")

        mvc.get("/api/v1/chaves/{chave}", "FULANO@exemplo.com").andExpect {
            status { isOk() }
            jsonPath("$.tipo") { value("EMAIL") }
        }
    }

    @Test
    fun `consulta de chave inexistente devolve 404`() {
        mvc.get("/api/v1/chaves/{chave}", "ninguem@exemplo.com").andExpect {
            status { isNotFound() }
            jsonPath("$.codigo") { value("CHAVE_NAO_ENCONTRADA") }
        }
    }

    @Test
    fun `consulta com formato irreconhecivel devolve 400`() {
        mvc.get("/api/v1/chaves/{chave}", "isso-nao-e-chave").andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("CHAVE_INVALIDA") }
        }
    }

    @Test
    fun `lista apenas as chaves ativas da conta`() {
        cadastrar(tipo = "EMAIL", valor = "um@exemplo.com", contaId = "conta-l")
        cadastrar(tipo = "EMAIL", valor = "dois@exemplo.com", contaId = "conta-l")
        cadastrar(tipo = "EMAIL", valor = "outra@exemplo.com", contaId = "conta-m", cpfTitular = CPF_B)
        mvc.delete("/api/v1/chaves/{chave}", "dois@exemplo.com") { header(HttpHeaders.AUTHORIZATION, CRED_TITULAR) }

        mvc.get("/api/v1/chaves") { param("contaId", conta("conta-l").toString()); header(HttpHeaders.AUTHORIZATION, CRED_TITULAR) }.andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(1))
            jsonPath("$[0].valor") { value("um@exemplo.com") }
        }
    }

    // ===================== Exclusão =====================

    @Test
    fun `exclusao e logica - preserva o registro e libera o valor para novo cadastro`() {
        cadastrar(tipo = "EMAIL", valor = "migra@exemplo.com", contaId = "conta-antiga", cpfTitular = CPF_A)

        mvc.delete("/api/v1/chaves/{chave}", "migra@exemplo.com") { header(HttpHeaders.AUTHORIZATION, CRED_TITULAR) }.andExpect { status { isNoContent() } }
        mvc.get("/api/v1/chaves/{chave}", "migra@exemplo.com").andExpect { status { isNotFound() } }

        // Portabilidade simplificada: a mesma chave em outra conta, de outro titular.
        cadastrar(tipo = "EMAIL", valor = "migra@exemplo.com", contaId = "conta-nova", cpfTitular = CPF_B)
            .andExpect { status { isCreated() } }

        // O histórico permanece: 1 registro excluído + 1 ativo.
        val porStatus = jdbc.queryForList("SELECT status FROM chave_pix WHERE valor = 'migra@exemplo.com'", String::class.java)
        assertEquals(listOf("ATIVA", "EXCLUIDA"), porStatus.sorted())
    }

    @Test
    fun `excluir chave inexistente devolve 404`() {
        mvc.delete("/api/v1/chaves/{chave}", "ninguem@exemplo.com") { header(HttpHeaders.AUTHORIZATION, CRED_TITULAR) }.andExpect { status { isNotFound() } }
    }

    // ===================== Apoio =====================

    private fun cadastrar(
        tipo: String,
        valor: String?,
        contaId: String = "conta-0001",
        cpfTitular: String = CPF_A,
        credencial: String? = CRED_TITULAR,
    ): ResultActionsDsl = mvc.post("/api/v1/chaves") {
        contentType = MediaType.APPLICATION_JSON
        credencial?.let { header(HttpHeaders.AUTHORIZATION, it) }
        val campoValor = valor?.let { "\"valor\": \"$it\"," } ?: ""
        content = """
            {
              "tipo": "$tipo",
              $campoValor
              "contaId": "${conta(contaId)}",
              "titular": { "nome": "Titular Sintético", "cpf": "$cpfTitular" }
            }
        """.trimIndent()
    }

    /** Converte um rótulo legível ("conta-a") num UUID fixo, como os ids da Ledger. */
    private fun conta(rotulo: String): UUID = UUID.nameUUIDFromBytes(rotulo.toByteArray())

    // ===================== Controle de acesso (sequestro de chave) =====================

    @Test
    fun `cadastrar chave sem credencial gera 401`() {
        cadastrar(tipo = "EMAIL", valor = "sem@exemplo.com", credencial = null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.codigo") { value("NAO_AUTENTICADO") }
        }
    }

    @Test
    fun `cadastrar chave na conta de outro titular gera 403`() {
        // O intruso não é dono de conta-0001 na Ledger simulada (fallback 404 = negado).
        cadastrar(tipo = "EMAIL", valor = "golpe@exemplo.com", credencial = CRED_INTRUSO).andExpect {
            status { isForbidden() }
            jsonPath("$.codigo") { value("ACESSO_NEGADO") }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chave_pix", Int::class.java))
    }

    @Test
    fun `terceiro nao consegue excluir a chave alheia para sequestra-la`() {
        cadastrar(tipo = "EMAIL", valor = "loja-alvo@exemplo.com").andExpect { status { isCreated() } }

        mvc.delete("/api/v1/chaves/{chave}", "loja-alvo@exemplo.com") { header(HttpHeaders.AUTHORIZATION, CRED_INTRUSO) }
            .andExpect { status { isForbidden() } }
        mvc.delete("/api/v1/chaves/{chave}", "loja-alvo@exemplo.com").andExpect { status { isUnauthorized() } }

        // A chave continua ativa e apontando para a conta original.
        mvc.get("/api/v1/chaves/{chave}", "loja-alvo@exemplo.com").andExpect {
            status { isOk() }
            jsonPath("$.contaId") { value(conta("conta-0001").toString()) }
        }
    }

    @Test
    fun `listar chaves de conta alheia gera 403 e a consulta de uma chave segue publica`() {
        cadastrar(tipo = "EMAIL", valor = "publica@exemplo.com")

        mvc.get("/api/v1/chaves") { param("contaId", conta("conta-0001").toString()); header(HttpHeaders.AUTHORIZATION, CRED_INTRUSO) }
            .andExpect { status { isForbidden() } }
        mvc.get("/api/v1/chaves/{chave}", "publica@exemplo.com").andExpect { status { isOk() } }
    }

    companion object {
        const val TITULAR = "titular-teste"
        const val SENHA_TITULAR = "senha-titular"
        val CRED_TITULAR = LedgerSimuladaTest.basic(TITULAR, SENHA_TITULAR)

        // CPFs sintéticos com dígitos verificadores válidos.
        const val CPF_A = "12345678909"
        const val CPF_B = "52998224725"
    }
}
