package com.kaizen.assistant

/**
 * A small recursive-descent arithmetic evaluator: + - * / ^ ( ) and decimals.
 * No external dependency, so no Maven-coordinate to trust — just plain Kotlin,
 * which means basic math never needs a network call.
 */
object MathEval {

    fun looksLikeMath(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() &&
            t.matches(Regex("^[0-9+\\-*/^().\\s]+$")) &&
            t.any { it.isDigit() }
    }

    /** Returns the result, or null if the expression couldn't be parsed. */
    fun evaluate(expr: String): Double? = try {
        Parser(expr).parseFully()
    } catch (e: Exception) {
        null
    }

    private class Parser(input: String) {
        private val s = input.replace(" ", "")
        private var pos = 0

        fun parseFully(): Double {
            val result = parseExpr()
            if (pos != s.length) throw IllegalArgumentException("Unexpected trailing input")
            return result
        }

        private fun parseExpr(): Double { // + -
            var value = parseTerm()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos]; pos++
                val rhs = parseTerm()
                value = if (op == '+') value + rhs else value - rhs
            }
            return value
        }

        private fun parseTerm(): Double { // * /
            var value = parseFactor()
            while (pos < s.length && (s[pos] == '*' || s[pos] == '/')) {
                val op = s[pos]; pos++
                val rhs = parseFactor()
                value = if (op == '*') value * rhs else value / rhs
            }
            return value
        }

        private fun parseFactor(): Double { // ^  (right-associative)
            val base = parseUnary()
            if (pos < s.length && s[pos] == '^') {
                pos++
                val exponent = parseFactor()
                return Math.pow(base, exponent)
            }
            return base
        }

        private fun parseUnary(): Double {
            if (pos < s.length && s[pos] == '-') { pos++; return -parseUnary() }
            if (pos < s.length && s[pos] == '+') { pos++; return parseUnary() }
            return parseAtom()
        }

        private fun parseAtom(): Double {
            if (pos < s.length && s[pos] == '(') {
                pos++
                val value = parseExpr()
                if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("Missing )")
                pos++
                return value
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Expected a number at $pos")
            return s.substring(start, pos).toDouble()
        }
    }
}
