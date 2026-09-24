package dev.archetype.definitions

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** Pure bounded arithmetic. Identifiers are explicit result fields; there is no property traversal. */
object Expression {
    fun evaluate(source: String, values: Map<String, Double>, validateOnly: Boolean = false): Double {
        require(source.length in 1..256) { "expression length must be 1..256" }
        return Parser(source, values, validateOnly).parse()
    }

    private class Parser(val source: String, val values: Map<String, Double>, val validateOnly: Boolean) {
        var at = 0
        var nodes = 0

        fun parse(): Double {
            val value = sum()
            spaces()
            require(at == source.length) { "unexpected expression text at $at" }
            require(value.isFinite()) { "expression result must be finite" }
            return value
        }

        private fun sum(): Double {
            var value = product()
            while (true) {
                spaces()
                value = when {
                    take('+') -> value + product()
                    take('-') -> value - product()
                    else -> return finite(value)
                }
            }
        }

        private fun product(): Double {
            var value = unary()
            while (true) {
                spaces()
                value = when {
                    take('*') -> value * unary()
                    take('/') -> {
                        val divisor = unary()
                        if (!validateOnly) require(divisor != 0.0) { "division by zero" }
                        value / if (validateOnly && divisor == 0.0) 1.0 else divisor
                    }
                    else -> return finite(value)
                }
            }
        }

        private fun unary(): Double {
            spaces()
            require(++nodes <= 128) { "expression is too complex" }
            return when {
                take('+') -> unary()
                take('-') -> -unary()
                take('(') -> sum().also { spaces(); require(take(')')) { "missing ')'" } }
                at < source.length && (source[at].isDigit() || source[at] == '.') -> number()
                else -> identifierOrCall()
            }
        }

        private fun number(): Double {
            val start = at
            while (at < source.length && (source[at].isDigit() || source[at] == '.')) at++
            return source.substring(start, at).toDoubleOrNull() ?: throw IllegalArgumentException("invalid number")
        }

        private fun identifierOrCall(): Double {
            val start = at
            while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_' || source[at] == '.')) at++
            require(start != at) { "expected a number or variable at $at" }
            val name = source.substring(start, at)
            spaces()
            if (take('(')) {
                val args = mutableListOf<Double>()
                spaces()
                if (!take(')')) {
                    do { args += sum(); spaces() } while (take(','))
                    require(take(')')) { "missing ')'" }
                }
                return when (name) {
                    "min" -> args.exactly(2).let { minOf(it[0], it[1]) }
                    "max" -> args.exactly(2).let { maxOf(it[0], it[1]) }
                    "clamp" -> args.exactly(3).let { it[0].coerceIn(minOf(it[1], it[2]), maxOf(it[1], it[2])) }
                    "abs" -> abs(args.exactly(1)[0])
                    "floor" -> floor(args.exactly(1)[0])
                    "ceil" -> ceil(args.exactly(1)[0])
                    "round" -> round(args.exactly(1)[0])
                    else -> throw IllegalArgumentException("unsupported function $name")
                }
            }
            require(Regex("result\\.[a-z0-9_./-]+\\.(health_lost|health_restored|amount)").matches(name)) {
                "unsupported variable $name"
            }
            return if (validateOnly) 1.0 else values[name] ?: throw IllegalArgumentException("result $name is unavailable")
        }

        private fun List<Double>.exactly(count: Int): List<Double> {
            require(size == count) { "function expects $count arguments" }
            return this
        }

        private fun finite(value: Double): Double {
            require(value.isFinite() || validateOnly) { "expression result must be finite" }
            return value
        }

        private fun spaces() { while (at < source.length && source[at].isWhitespace()) at++ }
        private fun take(char: Char): Boolean = if (at < source.length && source[at] == char) { at++; true } else false
    }
}
