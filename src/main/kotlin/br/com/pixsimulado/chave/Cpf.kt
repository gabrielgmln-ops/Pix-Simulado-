package br.com.pixsimulado.chave

/**
 * Validação, normalização e mascaramento de CPF.
 *
 * Algoritmo dos dígitos verificadores (módulo 11):
 *  - 1º dígito: soma dos 9 primeiros dígitos multiplicados pelos pesos 10..2;
 *  - 2º dígito: soma dos 10 primeiros dígitos multiplicados pelos pesos 11..2;
 *  - em ambos: dígito = (soma * 10) % 11, e se o resultado for 10, vale 0.
 */
object Cpf {

    /** Aceita "12345678909" ou "123.456.789-09" (separadores opcionais). */
    private val FORMATO = Regex("""^\d{3}\.?\d{3}\.?\d{3}-?\d{2}$""")

    /**
     * Retorna o CPF somente com dígitos, ou null se for inválido.
     * Sequências repetidas (000..., 111...) passam no módulo 11, mas não são CPFs reais.
     */
    fun normalizar(bruto: String): String? {
        val texto = bruto.trim()
        if (!FORMATO.matches(texto)) return null

        val digitos = texto.filter(Char::isDigit)
        if (digitos.all { it == digitos[0] }) return null

        val dv1 = digitoVerificador(digitos.substring(0, 9))
        val dv2 = digitoVerificador(digitos.substring(0, 10))
        return if (digitos[9].digitToInt() == dv1 && digitos[10].digitToInt() == dv2) digitos else null
    }

    fun isValido(bruto: String): Boolean = normalizar(bruto) != null

    /**
     * Máscara no padrão usado pelo DICT do Bacen: exibe apenas os 6 dígitos centrais.
     * "12345678909" -> "***.456.789-**". Reduz exposição de dado pessoal (LGPD).
     */
    fun mascarar(cpfNormalizado: String): String {
        require(cpfNormalizado.length == 11) { "CPF deve estar normalizado (11 dígitos)" }
        return "***.${cpfNormalizado.substring(3, 6)}.${cpfNormalizado.substring(6, 9)}-**"
    }

    /** Calcula um dígito verificador; os pesos começam em (tamanho + 1) e descem até 2. */
    private fun digitoVerificador(base: String): Int {
        val soma = base.mapIndexed { i, c -> c.digitToInt() * (base.length + 1 - i) }.sum()
        val resto = soma * 10 % 11
        return if (resto == 10) 0 else resto
    }
}
