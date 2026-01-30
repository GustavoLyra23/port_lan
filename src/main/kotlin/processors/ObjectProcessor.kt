package processors

import models.Environment
import models.Value
import models.errors.SemanticError
import org.gustavolyra.portugolpp.PortugolPPParser.DeclaracaoClasseContext
import org.gustavolyra.portugolpp.PortugolPPParser.ProgramaContext

fun getSuperClass(ctx: DeclaracaoClasseContext): String? =
    if (ctx.childCount > 3 && ctx.getChild(2).text == "estende") ctx.getChild(3).text else null


fun validateSuperClass(superClass: String, className: String, global: Environment) {
    global.getClass(superClass)
        ?: throw SemanticError("Classe base '$superClass' não encontrada para a classe '$className'")
}

fun getIndexFromWord(ctx: DeclaracaoClasseContext, word: String): Int {
    for (i in 0 until ctx.childCount) if (ctx.getChild(i).text == word) return i
    return -1
}

fun readIdentitiesToKey(ctx: DeclaracaoClasseContext, inicio: Int): List<String> {
    val lista = mutableListOf<String>()
    var i = inicio
    while (i < ctx.childCount && ctx.getChild(i).text != "{") {
        val t = ctx.getChild(i).text
        if (t != "," && t != "implementa") lista.add(t)
        i++
    }
    return lista
}

fun validateInterface(
    classeCtx: DeclaracaoClasseContext,
    nomeClasse: String,
    interfaces: List<String>,
    global: Environment
) {
    interfaces.forEach { nome ->
        global.getInterface(nome)
            ?: throw SemanticError("Interface '$nome' não encontrada")
        if (!validateInterfaceImplementation(classeCtx, nome, global)) {
            throw SemanticError("A classe '$nomeClasse' não implementa todos os métodos da interface '$nome'")
        }
    }
}

fun validateInterfaceImplementation(
    classeContext: DeclaracaoClasseContext,
    nomeInterface: String,
    global: Environment
): Boolean {
    val iface = global.getInterface(nomeInterface) ?: return false

    val fornecidos = buildSet<String> {
        addAll(classeContext.declaracaoFuncao().map { it.ID().text })
        global.getSuperClasse(classeContext)
            ?.let { global.getClass(it) }
            ?.let { addAll(it.declaracaoFuncao().map { f -> f.ID().text }) }
    }
    return iface.assinaturaMetodo().all { it.ID().text in fornecidos }
}

fun visitClasses(tree: ProgramaContext, global: Environment) {
    tree.declaracao().forEach { decl ->
        decl.declaracaoClasse()?.let {
            val nome = it.ID(0).text
            global.defineClass(nome, it)
        }
    }
}

fun visitInterfaces(tree: ProgramaContext, global: Environment) {
    tree.declaracao().forEach { decl ->
        decl.declaracaoInterface()?.let {
            val nome = it.ID().text
            global.setInterface(nome, it)
        }
    }
}

fun asObjectOrError(v: Value): Value.Object =
    v as? Value.Object
        ?: throw SemanticError("Nao e possivel acessar propriedades de um nao-objeto: $v")
