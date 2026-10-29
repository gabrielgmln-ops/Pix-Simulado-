package br.com.pixsimulado.chave

import br.com.pixsimulado.config.OpenApiConfig
import br.com.pixsimulado.pagamento.AutorizacaoConta
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/**
 * API REST de chaves Pix. Camada fina: converte HTTP <-> domínio e delega ao serviço.
 * Os recursos são endereçados pelo próprio valor da chave, como no DICT.
 *
 * Controle de acesso (contra sequestro de chave): cadastrar, excluir e listar exigem
 * HTTP Basic do DONO da conta, conferido na Ledger. Sem isso, um atacante apagaria a
 * chave da loja e a recadastraria na própria conta, desviando os Pix em silêncio.
 * A consulta de uma chave segue pública: é o que o pagador faz antes de pagar (DICT),
 * e ela não expõe dado sensível (CPF mascarado).
 */
@RestController
@RequestMapping("/api/v1/chaves")
@Tag(name = "Chaves Pix", description = "Cadastro, consulta e exclusão de chaves")
class ChavePixController(
    private val service: ChavePixService,
    private val autorizacao: AutorizacaoConta,
) {

    @PostMapping
    @SecurityRequirement(name = OpenApiConfig.BASIC)
    @Operation(summary = "Cadastra uma chave Pix (somente o dono da conta)")
    @ApiResponse(responseCode = "201", description = "Chave cadastrada")
    @ApiResponse(responseCode = "400", description = "Dados em formato inválido")
    @ApiResponse(responseCode = "401", description = "Credencial ausente ou recusada")
    @ApiResponse(responseCode = "403", description = "A conta não pertence ao usuário autenticado")
    @ApiResponse(responseCode = "409", description = "Chave já cadastrada")
    @ApiResponse(responseCode = "422", description = "Regra de negócio violada (limite, titularidade...)")
    fun cadastrar(
        @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) credencial: String?,
        @Valid @RequestBody request: CadastrarChaveRequest,
    ): ResponseEntity<ChavePixResponse> {
        val comando = request.paraComando()
        autorizacao.exigirDono(credencial, comando.contaId)
        val chave = service.cadastrar(comando)
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{chave}")
            .buildAndExpand(chave.valor)
            .encode()
            .toUri()
        return ResponseEntity.created(location).body(ChavePixResponse.de(chave))
    }

    @GetMapping("/{chave}")
    @Operation(summary = "Consulta uma chave ativa (tipo identificado automaticamente)")
    @ApiResponse(responseCode = "200", description = "Chave encontrada")
    @ApiResponse(responseCode = "400", description = "Formato de chave inválido")
    @ApiResponse(responseCode = "404", description = "Chave não encontrada")
    fun consultar(
        @Parameter(description = "CPF, e-mail ou chave aleatória", example = "123.456.789-09")
        @PathVariable chave: String,
    ): ChavePixResponse = ChavePixResponse.de(service.consultar(chave))

    @GetMapping
    @SecurityRequirement(name = OpenApiConfig.BASIC)
    @Operation(summary = "Lista as chaves ativas de uma conta (somente o dono)")
    @ApiResponse(responseCode = "401", description = "Credencial ausente ou recusada")
    @ApiResponse(responseCode = "403", description = "A conta não pertence ao usuário autenticado")
    fun listarPorConta(
        @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) credencial: String?,
        @RequestParam contaId: UUID,
    ): List<ChavePixResponse> {
        autorizacao.exigirDono(credencial, contaId)
        return service.listarPorConta(contaId).map(ChavePixResponse::de)
    }

    @DeleteMapping("/{chave}")
    @SecurityRequirement(name = OpenApiConfig.BASIC)
    @Operation(summary = "Exclui (logicamente) uma chave ativa (somente o dono da conta)")
    @ApiResponse(responseCode = "204", description = "Chave excluída")
    @ApiResponse(responseCode = "401", description = "Credencial ausente ou recusada")
    @ApiResponse(responseCode = "403", description = "A chave pertence à conta de outro titular")
    @ApiResponse(responseCode = "404", description = "Chave não encontrada")
    fun excluir(
        @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) credencial: String?,
        @PathVariable chave: String,
    ): ResponseEntity<Void> {
        autorizacao.exigirCredencial(credencial)
        // A consulta é pública, então 403 aqui não revela nada que o 404 esconderia.
        autorizacao.exigirDono(credencial, service.consultar(chave).contaId)
        service.excluir(chave)
        return ResponseEntity.noContent().build()
    }
}
