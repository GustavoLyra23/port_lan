import models.Valor
import org.gustavolyra.portugolpp.PortugolPPParser.ChamadaContext

fun extractValueToPrint(valor: Valor): String {
    return when (valor) {
        is Valor.Lista -> {
            val elementos = valor.elementos.map { extractValueToPrint(it) }
            "[${elementos.joinToString(", ")}]"
        }

        is Valor.Mapa -> {
            val entradas = valor.elementos.map { (chave, valor) ->
                "${extractValueToPrint(chave)}: ${extractValueToPrint(valor)}"
            }
            "[[${entradas.joinToString(", ")}]]"
        }

        is Valor.Texto -> "\"${valor.valor}\""
        is Valor.Inteiro -> valor.valor.toString()
        is Valor.Real -> valor.valor.toString()
        is Valor.Logico -> if (valor.valor) "verdadeiro" else "falso"
        is Valor.Objeto -> "[Objeto ${valor.klass}]"
        is Valor.Funcao -> "[fun ${valor.nome}]"
        Valor.Nulo -> "nulo"
        else -> valor.toString()
    }
}

fun extractValueToString(valor: Valor): String {
    return when (valor) {
        is Valor.Inteiro -> valor.valor.toString()
        is Valor.Real -> valor.valor.toString()
        is Valor.Logico -> if (valor.valor) "verdadeiro" else "falso"
        is Valor.Nulo -> "nulo"
        is Valor.Texto -> valor.valor
        is Valor.Lista -> {
            val elementos = valor.elementos.map { extractValueToString(it) }
            "[${elementos.joinToString(", ")}]"
        }

        is Valor.Mapa -> {
            val entradas = valor.elementos.map { (chave, valor) ->
                "${extractValueToString(chave)}: ${extractValueToString(valor)}"
            }
            "[[${entradas.joinToString(", ")}]]"
        }

        else -> valor.toString()
    }
}

fun isDot(ctx: ChamadaContext, i: Int) =
    i < ctx.childCount && ctx.getChild(i).text == "."
