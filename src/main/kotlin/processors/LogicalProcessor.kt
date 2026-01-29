package processors

import models.Value

fun compare(operator: String, left: Value, right: Value): Value {
    return when {
        left is Value.Integer && right is Value.Integer -> Value.Logic(
            when (operator) {
                "<" -> left.value < right.value
                "<=" -> left.value <= right.value
                ">" -> left.value > right.value
                ">=" -> left.value >= right.value
                else -> false
            }
        )

        left is Value.Real && right is Value.Real -> Value.Logic(
            when (operator) {
                "<" -> left.value < right.value
                "<=" -> left.value <= right.value
                ">" -> left.value > right.value
                ">=" -> left.value >= right.value
                else -> false
            }
        )

        left is Value.Real && right is Value.Integer -> Value.Logic(
            when (operator) {
                "<" -> left.value < right.value.toDouble()
                "<=" -> left.value <= right.value.toDouble()
                ">" -> left.value > right.value.toDouble()
                ">=" -> left.value >= right.value.toDouble()
                else -> false
            }
        )

        left is Value.Integer && right is Value.Real -> Value.Logic(
            when (operator) {
                "<" -> left.value.toDouble() < right.value
                "<=" -> left.value.toDouble() <= right.value
                ">" -> left.value.toDouble() > right.value
                ">=" -> left.value.toDouble() >= right.value
                else -> false
            }
        )

        left is Value.Text && right is Value.Text -> Value.Logic(
            when (operator) {
                "<" -> left.value < right.value
                "<=" -> left.value <= right.value
                ">" -> left.value > right.value
                ">=" -> left.value >= right.value
                else -> false
            }
        )

        else -> throw RuntimeException("Operador '$operator' não suportado para ${left::class.simpleName} e ${right::class.simpleName}")
    }
}

fun areEqual(left: Value, right: Value): Boolean {
    return when {
        left is Value.Integer && right is Value.Integer -> left.value == right.value
        left is Value.Real && right is Value.Real -> left.value == right.value
        left is Value.Real && right is Value.Integer -> left.value == right.value.toDouble()
        left is Value.Integer && right is Value.Real -> left.value.toDouble() == right.value
        left is Value.Text && right is Value.Text -> left.value == right.value
        left is Value.Logic && right is Value.Logic -> left.value == right.value
        left is Value.Object && right is Value.Object -> left === right
        left is Value.List && right is Value.List -> left === right
        left is Value.Map && right is Value.Map -> left === right
        else -> false
    }
}
