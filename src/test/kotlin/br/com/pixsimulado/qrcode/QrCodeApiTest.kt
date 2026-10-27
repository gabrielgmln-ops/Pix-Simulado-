package br.com.pixsimulado.qrcode

import br.com.pixsimulado.IntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import java.util.Base64
import kotlin.test.assertEquals

/** Fluxo completo: chave cadastrada -> QR gerado -> QR lido (texto e imagem). */
class QrCodeApiTest : IntegrationTest() {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper

    @BeforeEach
    fun prepararChave() {
        jdbc.execute("TRUNCATE chave_pix")
        mvc.post("/api/v1/chaves") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"tipo": "EMAIL", "valor": "loja.sintetica@exemplo.com", "contaId": "conta-loja",
                 "titular": {"nome": "Loja Sintética Ltda", "cpf": "12345678909"}}
            """
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `gera QR Code com payload valido e PNG que decodifica para o mesmo payload`() {
        val resposta = gerar("""{"chave": "LOJA.SINTETICA@exemplo.com", "valor": 25.9, "txid": "PEDIDO123", "descricao": "Pedido 123"}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.dados.chave") { value("loja.sintetica@exemplo.com") }
                jsonPath("$.dados.valor") { value(25.90) }
                jsonPath("$.dados.nomeRecebedor") { value("Loja Sintetica Ltda") }
                jsonPath("$.dados.cidade") { value("SAO PAULO") }
                jsonPath("$.dados.txid") { value("PEDIDO123") }
            }.andReturn().response.contentAsString

        val corpo = json.readTree(resposta)
        val payload = corpo["payload"].asText()
        val png = Base64.getDecoder().decode(corpo["imagemPngBase64"].asText())

        assertEquals(payload, QrCodeImagem.lerTexto(png))
        BrCode.ler(payload) // CRC e estrutura válidos
    }

    @Test
    fun `nao gera QR Code para chave inexistente`() {
        gerar("""{"chave": "ninguem@exemplo.com"}""").andExpect {
            status { isNotFound() }
            jsonPath("$.codigo") { value("CHAVE_NAO_ENCONTRADA") }
        }
    }

    @Test
    fun `valor com 3 casas decimais e recusado na entrada`() {
        gerar("""{"chave": "loja.sintetica@exemplo.com", "valor": 10.005}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.erros[0].campo") { value("valor") }
        }
    }

    @Test
    fun `txid com caracteres especiais e recusado`() {
        gerar("""{"chave": "loja.sintetica@exemplo.com", "txid": "pedido-123"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.erros[0].campo") { value("txid") }
        }
    }

    @Test
    fun `le payload em texto (copia e cola)`() {
        val payload = payloadGerado()

        mvc.post("/api/v1/qrcodes/leitura") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("payload" to payload))
        }.andExpect {
            status { isOk() }
            jsonPath("$.chave") { value("loja.sintetica@exemplo.com") }
            jsonPath("$.valor") { value(99.99) }
        }
    }

    @Test
    fun `leitura de payload adulterado devolve 400 com codigo de CRC`() {
        val adulterado = payloadGerado().replace("99.99", "10.00")

        mvc.post("/api/v1/qrcodes/leitura") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("payload" to adulterado))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("QRCODE_CRC_INVALIDO") }
        }
    }

    @Test
    fun `renderiza payload como PNG via GET`() {
        val payload = payloadGerado()

        val png = mvc.get("/api/v1/qrcodes/imagem") {
            param("payload", payload)
            param("tamanho", "250")
            accept = MediaType.IMAGE_PNG
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.IMAGE_PNG) }
            header { string("Cache-Control", "max-age=86400") }
        }.andReturn().response.contentAsByteArray

        assertEquals(payload, QrCodeImagem.lerTexto(png))
    }

    @Test
    fun `renderizacao de payload invalido devolve 400 mesmo pedindo imagem`() {
        mvc.get("/api/v1/qrcodes/imagem") {
            param("payload", "lixo")
            accept = MediaType.IMAGE_PNG
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `tamanho de imagem fora do limite devolve 400`() {
        mvc.get("/api/v1/qrcodes/imagem") {
            param("payload", payloadGerado())
            param("tamanho", "5000")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `le QR Code a partir de upload de imagem PNG`() {
        val png = QrCodeImagem.gerarPng(payloadGerado())

        mvc.multipart("/api/v1/qrcodes/leitura/imagem") {
            file(MockMultipartFile("arquivo", "qrcode.png", MediaType.IMAGE_PNG_VALUE, png))
        }.andExpect {
            status { isOk() }
            jsonPath("$.chave") { value("loja.sintetica@exemplo.com") }
            jsonPath("$.valor") { value(99.99) }
        }
    }

    @Test
    fun `upload de arquivo que nao e imagem devolve 400`() {
        mvc.multipart("/api/v1/qrcodes/leitura/imagem") {
            file(MockMultipartFile("arquivo", "x.txt", MediaType.TEXT_PLAIN_VALUE, "texto".toByteArray()))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.codigo") { value("IMAGEM_INVALIDA") }
        }
    }

    private fun gerar(corpo: String) = mvc.post("/api/v1/qrcodes") {
        contentType = MediaType.APPLICATION_JSON
        content = corpo
    }

    private fun payloadGerado(): String {
        val resposta = gerar("""{"chave": "loja.sintetica@exemplo.com", "valor": 99.99}""")
            .andReturn().response.contentAsString
        return json.readTree(resposta)["payload"].asText()
    }
}
