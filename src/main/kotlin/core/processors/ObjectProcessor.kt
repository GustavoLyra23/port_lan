package core.processors

import models.Environment
import models.Value
import models.errors.SemanticError
import org.gustavolyra.PlarParser.DeclaracaoClasseContext
import org.gustavolyra.PlarParser.ProgramaContext

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

fun readIdentitiesToKey(ctx: DeclaracaoClasseContext, start: Int): List<String> {
    val list = mutableListOf<String>()
    var i = start
    while (i < ctx.childCount && ctx.getChild(i).text != "{") {
        val t = ctx.getChild(i).text
        if (t != "," && t != "implementa") list.add(t)
        i++
    }
    return list
}

fun validateInterface(
    classeCtx: DeclaracaoClasseContext,
    className: String,
    interfaces: List<String>,
    global: Environment
) {
    interfaces.forEach { nome ->
        global.getInterface(nome)
            ?: throw SemanticError("Interface '$nome' não encontrada")
        if (!validateInterfaceImplementation(classeCtx, nome, global)) {
            throw SemanticError("A classe '$className' não implementa todos os métodos da interface '$nome'")
        }
    }
}

fun validateInterfaceImplementation(
    classeContext: DeclaracaoClasseContext,
    interfaceName: String,
    global: Environment
): Boolean {
    val iface = global.getInterface(interfaceName) ?: return false

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
