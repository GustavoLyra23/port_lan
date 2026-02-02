package core.processors

import models.Environment


fun isReturnInvalid(retorno: String?, global: Environment): Boolean {
    if (retorno == null) return false
    return retorno !in listOf(
        "Inteiro", "Real", "Texto", "Logico", "Nulo", "Lista", "Mapa"
    ) && (!global.classExists(retorno) && !global.interfaceExists(retorno))
}

