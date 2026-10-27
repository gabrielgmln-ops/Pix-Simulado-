package br.com.pixsimulado.shared.domain

/**
 * Hierarquia de exceções de negócio.
 *
 * O domínio não conhece HTTP: ele apenas classifica o erro em uma das quatro categorias
 * abaixo. A tradução para status HTTP fica no GlobalExceptionHandler. Por ser "sealed",
 * o compilador garante que todo novo tipo de erro tenha um mapeamento explícito.
 *
 * @property codigo identificador estável e legível por máquina (ex.: CHAVE_JA_CADASTRADA),
 *                  para que clientes tratem o erro sem depender do texto da mensagem.
 */
sealed class NegocioException(val codigo: String, mensagem: String) : RuntimeException(mensagem)

/** Dado de entrada em formato inválido (ex.: CPF com dígito verificador errado). HTTP 400. */
class EntradaInvalidaException(codigo: String, mensagem: String) : NegocioException(codigo, mensagem)

/** Recurso inexistente (ex.: chave não cadastrada). HTTP 404. */
class RecursoNaoEncontradoException(codigo: String, mensagem: String) : NegocioException(codigo, mensagem)

/** Conflito com o estado atual (ex.: chave já cadastrada). HTTP 409. */
class ConflitoException(codigo: String, mensagem: String) : NegocioException(codigo, mensagem)

/** Entrada bem formada, mas que viola uma regra de negócio (ex.: limite de chaves). HTTP 422. */
class RegraNegocioException(codigo: String, mensagem: String) : NegocioException(codigo, mensagem)
