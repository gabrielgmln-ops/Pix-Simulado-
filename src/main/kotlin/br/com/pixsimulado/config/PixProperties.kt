package br.com.pixsimulado.config

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Parâmetros da instituição simulada.
 *
 * @property ispb identificador de 8 dígitos da instituição no arranjo Pix
 *                (compõe o EndToEndId na Etapa 3). Valor sintético.
 * @property nomeInstituicao nome exibido em comprovantes e no payload do QR Code.
 * @property cidade cidade do recebedor, campo obrigatório do payload EMV (Etapa 2).
 */
@Validated
@ConfigurationProperties(prefix = "pix")
data class PixProperties(
    @field:Pattern(regexp = "\\d{8}", message = "ISPB deve conter exatamente 8 dígitos")
    val ispb: String,
    @field:NotBlank val nomeInstituicao: String,
    @field:NotBlank val cidade: String,
)
