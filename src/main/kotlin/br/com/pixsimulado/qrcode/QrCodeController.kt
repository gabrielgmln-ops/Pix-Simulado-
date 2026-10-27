package br.com.pixsimulado.qrcode

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration

@RestController
@RequestMapping("/api/v1/qrcodes")
// Restrições em @RequestParam (@Min/@Max) são validadas nativamente pelo Spring MVC 6.1+
// e resultam em 400 Problem Details, sem precisar de @Validated na classe.
@Tag(name = "QR Code", description = "Geração e leitura de BR Code estático")
class QrCodeController(private val service: QrCodeService) {

    @PostMapping
    @Operation(summary = "Gera QR Code estático para uma chave ativa (payload + PNG em Base64)")
    @ApiResponse(responseCode = "200", description = "QR Code gerado")
    @ApiResponse(responseCode = "400", description = "Dados inválidos")
    @ApiResponse(responseCode = "404", description = "Chave não encontrada")
    fun gerar(@Valid @RequestBody request: GerarQrCodeRequest): QrCodeGeradoResponse {
        val gerado = service.gerar(request.paraComando())
        val png = QrCodeImagem.gerarPng(gerado.payload, request.tamanho ?: QrCodeImagem.TAMANHO_PADRAO)
        return QrCodeGeradoResponse.de(gerado, png)
    }

    /**
     * Renderiza qualquer BR Code válido como PNG. Por ser GET, abre direto no navegador
     * ou em <img src="...">. O payload deve ir codificado na URL (URL-encoded).
     */
    @GetMapping("/imagem", produces = [MediaType.IMAGE_PNG_VALUE])
    @Operation(summary = "Renderiza um BR Code como imagem PNG")
    @ApiResponse(
        responseCode = "200",
        content = [Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = Schema(type = "string", format = "binary"))],
    )
    fun imagem(
        @RequestParam payload: String,
        @RequestParam(defaultValue = "${QrCodeImagem.TAMANHO_PADRAO}")
        @Min(QrCodeImagem.TAMANHO_MINIMO.toLong()) @Max(QrCodeImagem.TAMANHO_MAXIMO.toLong())
        tamanho: Int,
    ): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            // O mesmo payload sempre gera a mesma imagem: pode ser cacheada.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
            .contentType(MediaType.IMAGE_PNG)
            .body(service.renderizar(payload, tamanho))

    @PostMapping("/leitura")
    @Operation(summary = "Lê um BR Code em texto (Pix Copia e Cola)")
    @ApiResponse(responseCode = "200", description = "Payload válido")
    @ApiResponse(responseCode = "400", description = "Payload malformado ou CRC inválido")
    fun ler(@Valid @RequestBody request: LerQrCodeRequest): BrCodeResponse =
        BrCodeResponse.de(service.ler(request.payload!!))

    @PostMapping("/leitura/imagem", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Lê um QR Code a partir de uma imagem PNG/JPEG")
    @ApiResponse(responseCode = "200", description = "QR Code lido")
    @ApiResponse(responseCode = "400", description = "Imagem ilegível ou payload inválido")
    fun lerImagem(@RequestPart("arquivo") arquivo: MultipartFile): BrCodeResponse =
        BrCodeResponse.de(service.lerImagem(arquivo.bytes))
}
