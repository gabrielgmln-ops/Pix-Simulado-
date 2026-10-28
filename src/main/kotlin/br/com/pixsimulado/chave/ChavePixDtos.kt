package br.com.pixsimulado.chave

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** UUID em qualquer versão, sem chaves; comparado sem diferenciar maiúsculas. */
const val UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

/*
 * DTOs da API de chaves.
 * Campos de entrada são anuláveis de propósito: assim um campo ausente chega até o
 * Bean Validation e gera um erro por campo (400 com lista), em vez de uma falha
 * genérica de desserialização do Jackson.
 */

@Schema(description = "Cadastro de chave Pix")
data class CadastrarChaveRequest(
    @field:NotNull
    @Schema(example = "EMAIL")
    val tipo: TipoChave?,

    @Schema(
        description = "Valor da chave. Obrigatório para CPF e EMAIL; proibido para ALEATORIA (gerada pelo sistema).",
        example = "fulano.sintetico@exemplo.com",
    )
    val valor: String?,

    @field:NotBlank
    @field:Pattern(regexp = UUID_REGEX, message = "deve ser o UUID da conta na Ledger API")
    @Schema(description = "UUID da conta na Ledger API", example = "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b")
    val contaId: String?,

    @field:NotNull
    @field:Valid
    val titular: TitularRequest?,
) {
    fun paraComando() = NovaChave(
        tipo = tipo!!,               // !! seguro: garantido pelo @NotNull validado antes
        valor = valor,
        contaId = UUID.fromString(contaId!!),
        titularNome = titular!!.nome!!,
        titularCpf = titular.cpf!!,
    )
}

data class TitularRequest(
    @field:NotBlank
    @field:Size(max = 120)
    @Schema(example = "Fulano Sintético da Silva")
    val nome: String?,

    @field:NotBlank
    @Schema(description = "CPF com ou sem pontuação", example = "123.456.789-09")
    val cpf: String?,
)

@Schema(description = "Chave Pix")
data class ChavePixResponse(
    val tipo: TipoChave,
    @Schema(description = "Valor normalizado da chave")
    val valor: String,
    val contaId: UUID,
    val titular: TitularResponse,
    val criadaEm: Instant,
) {
    companion object {
        fun de(chave: ChavePix) = ChavePixResponse(
            tipo = chave.tipo,
            valor = chave.valor,
            contaId = chave.contaId,
            titular = TitularResponse(chave.titularNome, Cpf.mascarar(chave.titularCpf)),
            criadaEm = chave.criadaEm,
        )
    }
}

data class TitularResponse(
    val nome: String,
    @Schema(description = "CPF mascarado (LGPD)", example = "***.456.789-**")
    val cpf: String,
)
