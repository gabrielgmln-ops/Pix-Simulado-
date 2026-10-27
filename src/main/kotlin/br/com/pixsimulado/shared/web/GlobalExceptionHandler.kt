package br.com.pixsimulado.shared.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Tratamento centralizado de erros no formato RFC 9457 (Problem Details).
 *
 * Herdar de [ResponseEntityExceptionHandler] já converte os erros padrão do Spring MVC
 * (JSON malformado, validação de @Valid, método não suportado...) em ProblemDetail.
 * As exceções de domínio de cada etapa (chave duplicada, saldo insuficiente, etc.)
 * ganharão handlers específicos aqui.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

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
