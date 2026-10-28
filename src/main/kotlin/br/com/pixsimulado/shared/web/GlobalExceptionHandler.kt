package br.com.pixsimulado.shared.web

import br.com.pixsimulado.shared.domain.AcessoNegadoException
import br.com.pixsimulado.shared.domain.ConflitoException
import br.com.pixsimulado.shared.domain.EntradaInvalidaException
import br.com.pixsimulado.shared.domain.NaoAutenticadoException
import br.com.pixsimulado.shared.domain.NegocioException
import br.com.pixsimulado.shared.domain.RecursoNaoEncontradoException
import br.com.pixsimulado.shared.domain.RegraNegocioException
import br.com.pixsimulado.shared.domain.ServicoIndisponivelException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Tratamento centralizado de erros no formato RFC 9457 (Problem Details).
 *
 * Herdar de [ResponseEntityExceptionHandler] já converte os erros padrão do Spring MVC
 * (JSON malformado, método não suportado...) em ProblemDetail. Aqui acrescentamos:
 *  - erros de negócio, com a propriedade "codigo" estável para o cliente;
 *  - erros de validação, com a lista de campos inválidos.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NegocioException::class)
    fun handleNegocio(ex: NegocioException): ResponseEntity<ProblemDetail> {
        // "when" exaustivo: a hierarquia é sealed, então uma nova categoria sem
        // mapeamento aqui quebra a compilação em vez de virar 500 em produção.
        val (status, titulo) = when (ex) {
            is EntradaInvalidaException -> HttpStatus.BAD_REQUEST to "Entrada inválida"
            is RecursoNaoEncontradoException -> HttpStatus.NOT_FOUND to "Recurso não encontrado"
            is ConflitoException -> HttpStatus.CONFLICT to "Conflito"
            is RegraNegocioException -> HttpStatus.UNPROCESSABLE_ENTITY to "Regra de negócio violada"
            is NaoAutenticadoException -> HttpStatus.UNAUTHORIZED to "Não autenticado"
            is AcessoNegadoException -> HttpStatus.FORBIDDEN to "Acesso negado"
            is ServicoIndisponivelException -> HttpStatus.SERVICE_UNAVAILABLE to "Serviço indisponível"
        }
        val problema = ProblemDetail.forStatusAndDetail(status, ex.message).apply {
            title = titulo
            setProperty("codigo", ex.codigo)
        }
        val resposta = ResponseEntity.status(status)
        // RFC 9110: todo 401 diz ao cliente qual esquema de autenticação usar.
        if (ex is NaoAutenticadoException) resposta.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"pix-simulado\"")
        return resposta.body(problema)
    }

    /** 400 com um item por campo inválido: {"campo": "titular.cpf", "mensagem": "..."} */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problema = ex.body.apply {
            detail = "Um ou mais campos são inválidos"
            setProperty("codigo", "CAMPOS_INVALIDOS")
            setProperty(
                "erros",
                ex.bindingResult.fieldErrors
                    .sortedBy { it.field }
                    .map { mapOf("campo" to it.field, "mensagem" to (it.defaultMessage ?: "inválido")) },
            )
        }
        return handleExceptionInternal(ex, problema, headers, status, request)
    }

    /**
     * Rede de segurança: qualquer erro não previsto vira 500 com mensagem genérica.
     * O detalhe técnico vai somente para o log, nunca para o cliente.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Erro inesperado", ex)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Erro interno. Tente novamente mais tarde.",
        ).apply { title = "Erro interno" }
    }
}
