package core.processors

import extractValueToString
import models.Value

fun processAdd(operator: String?, left: Value, right: Value): Value {
    return when (operator) {
        "+" -> resolveAddOperator(left, right)
        "-" -> solveSubOperator(left, right)
        else -> {
            throw RuntimeException("Operador desconhecido: $operator")
        }
    }
}

fun resolveAddOperator(left: Value, right: Value): Value {
    return when {
        left is Value.Text || right is Value.Text -> {
            val leftStr = extractValueToString(left)
            val rightStr = extractValueToString(right)
            Value.Text(leftStr + rightStr)
        }

        left is Value.Integer && right is Value.Integer -> {
            val resultado = Value.Integer(left.value + right.value)
            resultado
        }

        left is Value.Real && right is Value.Real -> {
            val resultado = Value.Real(left.value + right.value)
            resultado
        }

        left is Value.Integer && right is Value.Real -> {
            val resultado = Value.Real(left.value.toDouble() + right.value)
            resultado
        }

        left is Value.Real && right is Value.Integer -> {
            val resultado = Value.Real(left.value + right.value.toDouble())
            resultado
        }

        else -> {
            throw RuntimeException("Operador '+' não suportado para ${left::class.simpleName} e ${right::class.simpleName}")
        }
    }
}

fun solveSubOperator(left: Value, right: Value): Value {
    return when {
        left is Value.Integer && right is Value.Integer -> {
            val resultado = Value.Integer(left.value - right.value)
            resultado
        }

        left is Value.Real && right is Value.Real -> {
            val resultado = Value.Real(left.value - right.value)
            resultado
        }

        left is Value.Integer && right is Value.Real -> {
            val resultado = Value.Real(left.value.toDouble() - right.value)
            resultado
        }

        left is Value.Real && right is Value.Integer -> {
            val resultado = Value.Real(left.value - right.value.toDouble())
            resultado
        }

        else -> {
            throw RuntimeException("Operador '-' não suportado para ${left::class.simpleName} e ${right::class.simpleName}")
        }
    }
}

fun processMultiplication(
    operador: String?, left: Value, right: Value
): Value {
    return when (operador) {
        "*" -> solveMultiplicationOperator(left, right)
        "/" -> solveDivisionOperator(left, right)
        "%" -> solveModuleOperator(left, right)
        else -> {
            throw RuntimeException("Operador desconhecido: $operador")
        }
    }
}

fun solveMultiplicationOperator(left: Value, right: Value): Value {
    return when {
        left is Value.Integer && right is Value.Integer -> Value.Integer(left.value * right.value)
        left is Value.Real && right is Value.Real -> Value.Real(left.value * right.value)
        left is Value.Integer && right is Value.Real -> Value.Real(left.value.toDouble() * right.value)
        left is Value.Real && right is Value.Integer -> Value.Real(left.value * right.value.toDouble())
        else -> throw RuntimeException("Operador '*' não suportado para ${left::class.simpleName} e ${right::class.simpleName}")
    }
}

fun solveModuleOperator(left: Value, right: Value): Value {
    return when {
        left is Value.Integer && right is Value.Integer -> {
            if (right.value == 0) throw RuntimeException("Módulo por zero")
            Value.Integer(left.value % right.value)
        }

        left is Value.Real && right is Value.Real -> {
            if (right.value == 0.0) throw RuntimeException("Módulo por zero")
            Value.Real(left.value % right.value)
        }

        left is Value.Integer && right is Value.Real -> {
            if (right.value == 0.0) throw RuntimeException("Módulo por zero")
            Value.Real(left.value.toDouble() % right.value)
        }

        left is Value.Real && right is Value.Integer -> {
            if (right.value == 0) throw RuntimeException("Módulo por zero")
            Value.Real(left.value % right.value.toDouble())
        }

        else -> throw RuntimeException("Operador '%' não suportado para ${left::class.simpleName} e ${right::class.simpleName}")
    }
}

fun solveDivisionOperator(left: Value, right: Value): Value {
    return when {
        (right is Value.Integer && right.value == 0) || (right is Value.Real && right.value == 0.0) -> throw RuntimeException(
            "Divisão por zero"
        )

        left is Value.Integer && right is Value.Integer -> if (left.value % right.value == 0) Value.Integer(
            left.value / right.value
        ) else Value.Real(left.value.toDouble() / right.value)

        left is Value.Real && right is Value.Real -> Value.Real(left.value / right.value)
        left is Value.Integer && right is Value.Real -> Value.Real(left.value.toDouble() / right.value)
        left is Value.Real && right is Value.Integer -> Value.Real(left.value / right.value.toDouble())
        else -> throw RuntimeException("Operador '/' não suportado para ${left::class.simpleName} e ${right::class.simpleName}")
    }
}
