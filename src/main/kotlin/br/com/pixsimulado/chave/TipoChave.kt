package br.com.pixsimulado.chave

import br.com.pixsimulado.shared.domain.EntradaInvalidaException

/**
 * Tipos de chave Pix suportados e as regras de formato de cada um.
 *
 * Cada tipo sabe validar e normalizar o próprio valor, de modo que o valor persistido
 * é sempre canônico: a mesma chave digitada de formas diferentes ("123.456.789-09"
 * e "12345678909"; "Fulano@X.com" e "fulano@x.com") colide no índice único.
 */
enum class TipoChave {

    /** CPF do titular, armazenado com 11 dígitos. */
    CPF {
        override fun normalizar(valor: String): String =
            Cpf.normalizar(valor) ?: throw chaveInvalida("CPF inválido")
    },

    /** E-mail em minúsculas; até 77 caracteres, limite adotado pelo DICT. */
    EMAIL {
        override fun normalizar(valor: String): String {
            val email = valor.trim().lowercase()
            if (email.length > TAMANHO_MAXIMO_EMAIL) {
                throw chaveInvalida("E-mail excede $TAMANHO_MAXIMO_EMAIL caracteres")
            }
            if (!REGEX_EMAIL.matches(email)) throw chaveInvalida("E-mail em formato inválido")
            return email
        }
    },

    /**
     * Chave aleatória: UUID versão 4, sempre gerado pelo servidor no cadastro.
     * Aqui só se valida o formato (para consultas) e se padroniza em minúsculas.
     */
    ALEATORIA {
        override fun normalizar(valor: String): String {
            val uuid = valor.trim().lowercase()
            if (!REGEX_UUID_V4.matches(uuid)) throw chaveInvalida("Chave aleatória deve ser um UUID v4")
            return uuid
        }
    };

    /** Valida e devolve o valor canônico; lança [EntradaInvalidaException] se inválido. */
    abstract fun normalizar(valor: String): String

    companion object {
        const val TAMANHO_MAXIMO_EMAIL = 77

        // Parte local permissiva (RFC 5322 simplificada); domínio com rótulos de até 63
        // caracteres e ao menos um ponto (exige TLD).
        private val REGEX_EMAIL = Regex(
            """^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$""",
        )

        // Versão "4" no 13º caractere e variante RFC 4122 (8, 9, a ou b) no 17º.
        private val REGEX_UUID_V4 =
            Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$""")

        private val REGEX_UUID_QUALQUER =
            Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

        /**
         * Descobre o tipo a partir do valor digitado, como faz um app bancário na tela
         * "Pagar com chave". Os formatos são disjuntos, então não há ambiguidade:
         * formato de UUID -> aleatória; contém "@" -> e-mail; caso contrário -> CPF.
         */
        fun inferir(valor: String): TipoChave {
            val texto = valor.trim()
            return when {
                REGEX_UUID_QUALQUER.matches(texto) -> ALEATORIA
                '@' in texto -> EMAIL
                texto.any(Char::isDigit) -> CPF
                else -> throw chaveInvalida("Não foi possível identificar o tipo da chave")
            }
        }

        /** Atalho para converter o valor bruto em (tipo, valor canônico). */
        fun identificar(valor: String): Pair<TipoChave, String> {
            val tipo = inferir(valor)
            return tipo to tipo.normalizar(valor)
        }

        private fun chaveInvalida(mensagem: String) = EntradaInvalidaException("CHAVE_INVALIDA", mensagem)
    }
}
