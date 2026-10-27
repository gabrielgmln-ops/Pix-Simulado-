package br.com.pixsimulado.chave

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * API REST de chaves Pix. Camada fina: converte HTTP <-> domínio e delega ao serviço.
 * Os recursos são endereçados pelo próprio valor da chave, como no DICT.
 */
@RestController
@RequestMapping("/api/v1/chaves")
@Tag(name = "Chaves Pix", description = "Cadastro, consulta e exclusão de chaves")
class ChavePixController(private val service: ChavePixService) {

    @PostMapping
    @Operation(summary = "Cadastra uma chave Pix")
    @ApiResponse(responseCode = "201", description = "Chave cadastrada")
    @ApiResponse(responseCode = "400", description = "Dados em formato inválido")
    @ApiResponse(responseCode = "409", description = "Chave já cadastrada")
    @ApiResponse(responseCode = "422", description = "Regra de negócio violada (limite, titularidade...)")
    fun cadastrar(@Valid @RequestBody request: CadastrarChaveRequest): ResponseEntity<ChavePixResponse> {
        val chave = service.cadastrar(request.paraComando())
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
    @Operation(summary = "Lista as chaves ativas de uma conta")
    fun listarPorConta(@RequestParam contaId: String): List<ChavePixResponse> =
        service.listarPorConta(contaId).map(ChavePixResponse::de)

    @DeleteMapping("/{chave}")
    @Operation(summary = "Exclui (logicamente) uma chave ativa")
    @ApiResponse(responseCode = "204", description = "Chave excluída")
    @ApiResponse(responseCode = "404", description = "Chave não encontrada")
    fun excluir(@PathVariable chave: String): ResponseEntity<Void> {
        service.excluir(chave)
        return ResponseEntity.noContent().build()
    }
}
