package br.com.pixsimulado.qrcode

import br.com.pixsimulado.chave.ChavePixService
import br.com.pixsimulado.config.PixProperties
import org.springframework.stereotype.Service
import java.math.BigDecimal

data class NovoQrCode(
    val chave: String,
    val valor: BigDecimal?,
    val descricao: String?,
    val txid: String?,
)

data class QrCodeGerado(
    val brCode: BrCode,
    val payload: String,
)

/**
 * Casos de uso de QR Code estático.
 *
 * QR Codes estáticos não são persistidos: o payload é autocontido e pode ser
 * regenerado a qualquer momento. A rastreabilidade de cada pagamento virá do
 * EndToEndId da transferência (Etapa 3), que poderá carregar o txid lido aqui.
 */
@Service
class QrCodeService(
    private val chaveService: ChavePixService,
    private val pixProperties: PixProperties,
) {

    /**
     * Só gera QR para chave ATIVA cadastrada aqui: o recebedor é o titular da chave
     * e o valor gravado no payload é o canônico (ex.: CPF sem pontuação).
     */
    fun gerar(comando: NovoQrCode): QrCodeGerado {
        val chave = chaveService.consultar(comando.chave)
        val brCode = BrCode.criar(
            chave = chave.valor,
            valor = comando.valor,
            nomeRecebedor = chave.titularNome,
            cidade = pixProperties.cidade,
            txid = comando.txid,
            descricao = comando.descricao,
        )
        return QrCodeGerado(brCode, brCode.gerar())
    }

    fun ler(payload: String): BrCode = BrCode.ler(payload)

    fun lerImagem(imagem: ByteArray): BrCode = BrCode.ler(QrCodeImagem.lerTexto(imagem))

    /** Renderiza um payload já existente, validando-o antes (não desenha lixo). */
    fun renderizar(payload: String, tamanho: Int): ByteArray {
        BrCode.ler(payload)
        return QrCodeImagem.gerarPng(payload.trim(), tamanho)
    }
}
