package processors

import extractValueToString
import models.Valor

fun processarAdicao(operador: String?, esquerda: Valor, direita: Valor): Valor {
    return when (operador) {
        "+" -> resolverOperadorAdicao(esquerda, direita)
        "-" -> resolverOperadorSubtracao(esquerda, direita)
        else -> {
            throw RuntimeException("Operador desconhecido: $operador")
        }
    }
}

fun resolverOperadorAdicao(esquerda: Valor, direita: Valor): Valor {
    return when {
        esquerda is Valor.Texto || direita is Valor.Texto -> {
            val esquerdaStr = extractValueToString(esquerda)
            val direitaStr = extractValueToString(direita)
            Valor.Texto(esquerdaStr + direitaStr)
        }

        esquerda is Valor.Inteiro && direita is Valor.Inteiro -> {
            val resultado = Valor.Inteiro(esquerda.valor + direita.valor)
            resultado
        }

        esquerda is Valor.Real && direita is Valor.Real -> {
            val resultado = Valor.Real(esquerda.valor + direita.valor)
            resultado
        }

        esquerda is Valor.Inteiro && direita is Valor.Real -> {
            val resultado = Valor.Real(esquerda.valor.toDouble() + direita.valor)
            resultado
        }

        esquerda is Valor.Real && direita is Valor.Inteiro -> {
            val resultado = Valor.Real(esquerda.valor + direita.valor.toDouble())
            resultado
        }

        else -> {
            throw RuntimeException("Operador '+' não suportado para ${esquerda::class.simpleName} e ${direita::class.simpleName}")
        }
    }
}

fun resolverOperadorSubtracao(esquerda: Valor, direita: Valor): Valor {
    return when {
        esquerda is Valor.Inteiro && direita is Valor.Inteiro -> {
            val resultado = Valor.Inteiro(esquerda.valor - direita.valor)
            resultado
        }

        esquerda is Valor.Real && direita is Valor.Real -> {
            val resultado = Valor.Real(esquerda.valor - direita.valor)
            resultado
        }

        esquerda is Valor.Inteiro && direita is Valor.Real -> {
            val resultado = Valor.Real(esquerda.valor.toDouble() - direita.valor)
            resultado
        }

        esquerda is Valor.Real && direita is Valor.Inteiro -> {
            val resultado = Valor.Real(esquerda.valor - direita.valor.toDouble())
            resultado
        }

        else -> {
            throw RuntimeException("Operador '-' não suportado para ${esquerda::class.simpleName} e ${direita::class.simpleName}")
        }
    }
}

fun processarMultiplicacao(
    operador: String?, esquerda: Valor, direita: Valor
): Valor {
    return when (operador) {
        "*" -> resolverOperadorMultiplicacao(esquerda, direita)
        "/" -> resolverOperadorDivisao(esquerda, direita)
        "%" -> resolverOperadorModulo(esquerda, direita)
        else -> {
            throw RuntimeException("Operador desconhecido: $operador")
        }
    }
}

fun resolverOperadorMultiplicacao(esquerda: Valor, direita: Valor): Valor {
    return when {
        esquerda is Valor.Inteiro && direita is Valor.Inteiro -> Valor.Inteiro(esquerda.valor * direita.valor)
        esquerda is Valor.Real && direita is Valor.Real -> Valor.Real(esquerda.valor * direita.valor)
        esquerda is Valor.Inteiro && direita is Valor.Real -> Valor.Real(esquerda.valor.toDouble() * direita.valor)
        esquerda is Valor.Real && direita is Valor.Inteiro -> Valor.Real(esquerda.valor * direita.valor.toDouble())
        else -> throw RuntimeException("Operador '*' não suportado para ${esquerda::class.simpleName} e ${direita::class.simpleName}")
    }
}

fun resolverOperadorModulo(esquerda: Valor, direita: Valor): Valor {
    return when {
        esquerda is Valor.Inteiro && direita is Valor.Inteiro -> {
            if (direita.valor == 0) throw RuntimeException("Módulo por zero")
            Valor.Inteiro(esquerda.valor % direita.valor)
        }

        esquerda is Valor.Real && direita is Valor.Real -> {
            if (direita.valor == 0.0) throw RuntimeException("Módulo por zero")
            Valor.Real(esquerda.valor % direita.valor)
        }

        esquerda is Valor.Inteiro && direita is Valor.Real -> {
            if (direita.valor == 0.0) throw RuntimeException("Módulo por zero")
            Valor.Real(esquerda.valor.toDouble() % direita.valor)
        }

        esquerda is Valor.Real && direita is Valor.Inteiro -> {
            if (direita.valor == 0) throw RuntimeException("Módulo por zero")
            Valor.Real(esquerda.valor % direita.valor.toDouble())
        }

        else -> throw RuntimeException("Operador '%' não suportado para ${esquerda::class.simpleName} e ${direita::class.simpleName}")
    }
}

fun resolverOperadorDivisao(esquerda: Valor, direita: Valor): Valor {
    return when {
        (direita is Valor.Inteiro && direita.valor == 0) || (direita is Valor.Real && direita.valor == 0.0) -> throw RuntimeException(
            "Divisão por zero"
        )

        esquerda is Valor.Inteiro && direita is Valor.Inteiro -> if (esquerda.valor % direita.valor == 0) Valor.Inteiro(
            esquerda.valor / direita.valor
        ) else Valor.Real(esquerda.valor.toDouble() / direita.valor)

        esquerda is Valor.Real && direita is Valor.Real -> Valor.Real(esquerda.valor / direita.valor)
        esquerda is Valor.Inteiro && direita is Valor.Real -> Valor.Real(esquerda.valor.toDouble() / direita.valor)
        esquerda is Valor.Real && direita is Valor.Inteiro -> Valor.Real(esquerda.valor / direita.valor.toDouble())
        else -> throw RuntimeException("Operador '/' não suportado para ${esquerda::class.simpleName} e ${direita::class.simpleName}")
    }
}
