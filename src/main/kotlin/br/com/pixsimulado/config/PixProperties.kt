package br.com.pixsimulado.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Parâmetros da instituição simulada.
 *
 * @property ispb identificador de 8 dígitos da instituição no arranjo Pix
 *                (compõe o EndToEndId na Etapa 3). Valor sintético.
 * @property nomeInstituicao nome exibido em comprovantes e no payload do QR Code.
 * @property cidade cidade do recebedor, campo obrigatório do payload EMV (Etapa 2).
 * @property chaves regras do cadastro de chaves.
 * @property devolucao regras das devoluções (Etapa 4).
 */
@Validated
@ConfigurationProperties(prefix = "pix")
data class PixProperties(
    @field:Pattern(regexp = "\\d{8}", message = "ISPB deve conter exatamente 8 dígitos")
    val ispb: String,
    @field:NotBlank val nomeInstituicao: String,
    @field:NotBlank val cidade: String,
    @field:Valid val chaves: Chaves = Chaves(),
    @field:Valid val devolucao: Devolucao = Devolucao(),
) {
    /**
     * @property limitePorConta máximo de chaves ATIVAS por conta. O regulamento do Pix
     *           fixa 5 para pessoa física (20 para jurídica, fora do escopo).
     */
    data class Chaves(
        @field:Min(1) val limitePorConta: Int = 5,
    )

    /**
     * @property prazo quanto tempo depois da liquidação o recebedor ainda pode devolver.
     *           O regulamento do Pix fixa 90 dias.
     */
    data class Devolucao(
        val prazo: Duration = Duration.ofDays(90),
    )
}
