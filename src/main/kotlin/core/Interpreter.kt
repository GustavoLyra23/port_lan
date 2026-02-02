package core

import core.processors.areEqual
import core.processors.asObjectOrError
import core.processors.compare
import core.processors.defineDefaultFunctions
import core.processors.getIndexFromWord
import core.processors.getSuperClass
import core.processors.isReturnInvalid
import core.processors.processAdd
import core.processors.processMultiplication
import core.processors.readIdentitiesToKey
import core.processors.validateInterface
import core.processors.validateSuperClass
import core.processors.visitClasses
import core.processors.visitInterfaces
import helpers.solvePath
import isDot
import models.Environment
import models.Value
import models.enums.LOOP
import models.errors.ArquivoException
import models.errors.BreakException
import models.errors.ContinueException
import models.errors.RetornoException
import models.errors.SemanticError
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.gustavolyra.PlarBaseVisitor
import org.gustavolyra.PlarLexer
import org.gustavolyra.PlarParser
import java.nio.file.Files

@Suppress("REDUNDANT_OVERRIDE")
class Interpreter : PlarBaseVisitor<Value>() {
    private var global = Environment()

    // actual exec environment
    private var environment = global

    // ref to the function being executed
    private var actualFunction: Value.Fun? = null

    private val importedModules = mutableSetOf<String>()

    init {
        defineDefaultFunctions(global)
    }

    override fun visitImportarDeclaracao(ctx: PlarParser.ImportarDeclaracaoContext): Value {
        val fileName = ctx.TEXTO_LITERAL().text.removeSurrounding("\"")
        processImport(fileName)
        return Value.Null
    }

    private fun processModuleDeclarations(tree: PlarParser.ProgramaContext) {
        tree.declaracao().forEach { dec ->
            dec.declaracaoInterface()?.let(::visitDeclaracaoInterface)
            dec.declaracaoClasse()?.let(::visitDeclaracaoClasse)
            dec.declaracaoFuncao()?.let(::visitDeclaracaoFuncao)
            dec.declaracaoVar()?.let(::visitDeclaracaoVar)
        }
    }


    fun processImport(filName: String) {
        if (importedModules.contains(filName)) return
        importedModules.add(filName)
        try {
            val path = solvePath(filName)
            val fileContent = Files.readString(path)
            val lexer = PlarLexer(CharStreams.fromString(fileContent))
            val tokens = CommonTokenStream(lexer)
            val parser = PlarParser(tokens)
            val tree = parser.programa()

            tree.importarDeclaracao().forEach { import ->
                visitImportarDeclaracao(import)
            }

            processModuleDeclarations(tree)
        } catch (e: Exception) {
            throw ArquivoException(e.message ?: "Falha ao processar import")
        }
    }

    fun interpret(tree: PlarParser.ProgramaContext) {
        try {
            tree.importarDeclaracao()?.forEach { import ->
                visitImportarDeclaracao(import)
            }
            visitInterfaces(tree, global)
            visitClasses(tree, global)
            tree.declaracao().forEach { visit(it) }
        } catch (e: Exception) {
            println(e)
        }
    }

    override fun visitDeclaracaoInterface(ctx: PlarParser.DeclaracaoInterfaceContext): Value {
        val interfaceName = ctx.ID().text
        global.setInterface(interfaceName, ctx)
        return Value.Null
    }

    override fun visitDeclaracaoTentarCapturar(ctx: PlarParser.DeclaracaoTentarCapturarContext?): Value {
        try {
            visit(ctx?.bloco(0))
        } catch (_: Exception) {
            visit(ctx?.bloco(1))
        }
        return Value.Null
    }

    override fun visitDeclaracaoClasse(ctx: PlarParser.DeclaracaoClasseContext): Value {
        val className = ctx.ID(0).text

        getSuperClass(ctx)?.let { sc ->
            validateSuperClass(sc, className, global)
        }
        getIndexFromWord(ctx, "implementa").takeIf { it >= 0 }?.let { idx ->
            val interfaceList = readIdentitiesToKey(ctx, idx + 1)
            validateInterface(ctx, className, interfaceList, global)
        }
        global.defineClass(className, ctx)
        return Value.Null
    }

    override fun visitDeclaracaoVar(ctx: PlarParser.DeclaracaoVarContext): Value {
        val name = ctx.ID().text
        val type = ctx.tipo()?.text
        val value = when {
            (ctx.expressao() != null) -> visit(ctx.expressao());
            else -> visit(ctx.declaracaoFuncao())
        }

        if (type != null) {
            if (value is Value.Object) {
                val className = value.klass
                if (type != className && value.superClass != type && !value.interfaces.contains(type)) throw SemanticError(
                    "Tipo de variavel '$type' nao corresponde ao tipo do objeto '$className'"
                )
            } else {
                if (type != value.typeString()) throw SemanticError("Tipo da variavel nao corresponde ao tipo correto atribuido.")
            }
        }
        environment.define(name, value)
        return Value.Null
    }

    override fun visitDeclaracaoFuncao(ctx: PlarParser.DeclaracaoFuncaoContext): Value {
        val name = ctx.ID().text
        val returnType = ctx.tipo()?.text
        if (isReturnInvalid(returnType, global)) throw SemanticError("Tipo de retorno invalido: $returnType")
        val func = Value.Fun(
            name = name,
            declaration = ctx,
            returnType = returnType,
            closure = environment,
            implementation = definirImplementacao(ctx, name, Environment(environment))
        )
        environment.define(name, func)
        return func
    }

    private fun definirImplementacao(
        ctx: PlarParser.DeclaracaoFuncaoContext, name: String, closure: Environment
    ): (List<Value>) -> Value {
        return { args ->
            val declaredParameters = ctx.listaParams()?.param()?.size ?: 0
            if (args.size > declaredParameters) throw SemanticError(
                "Funcao '$name' recebeu ${args.size} " +
                        "parametros, mas espera $declaredParameters"
            )
            ctx.listaParams()?.param()?.forEachIndexed { i, param ->
                if (i < args.size) closure.define(param.ID().text, args[i])
            }

            val actualEnviromnent = environment
            environment = closure
            val `fun` = Value.Fun(
                ctx.ID().text, ctx, ctx.tipo()?.text, global
            )
            val oldFunction = actualFunction
            actualFunction = `fun`
            try {
                visit(ctx.bloco())
                Value.Null
            } catch (err: RetornoException) {
                err.value
            } finally {
                environment = actualEnviromnent
                actualFunction = oldFunction
            }
        }
    }

    //TODO: refactor visit return declaration
    override fun visitDeclaracaoRetornar(ctx: PlarParser.DeclaracaoRetornarContext): Value {
        val returnVal = ctx.expressao()?.let { visit(it) } ?: Value.Null
        // apenas valida se estivermos dentro de uma funcao
        if (actualFunction != null && actualFunction!!.returnType != null) {
            val expectedType = actualFunction!!.returnType
            val actualType = returnVal.typeString()
            if (expectedType != actualType) {
                if (returnVal is Value.Object) {
                    //TODO: create validation for superclasses and interfaces
                    if (returnVal.superClass == expectedType || returnVal.interfaces.contains(expectedType)) throw RetornoException(
                        returnVal
                    )
                }
                throw SemanticError("Erro de tipo: funcao '${actualFunction!!.name}' deve retornar '$expectedType', mas esta retornando '$actualType'")
            }
        }
        throw RetornoException(returnVal)
    }

    override fun visitDeclaracaoSe(ctx: PlarParser.DeclaracaoSeContext): Value {
        val condition = visit(ctx.expressao())
        if (condition !is Value.Logic) throw SemanticError("Condicaoo do 'if' deve ser logica")
        return if (condition.value) visit(ctx.declaracao(0)) else ctx.declaracao(1)?.let { visit(it) } ?: Value.Null
    }

    override fun visitBloco(ctx: PlarParser.BlocoContext): Value {
        val previous = environment
        environment = Environment(previous)
        environment.thisObject = previous.thisObject
        try {
            ctx.declaracao().forEach { visit(it) }
        } finally {
            environment = previous
        }
        return Value.Null
    }

    override fun visitExpressao(ctx: PlarParser.ExpressaoContext): Value = visit(ctx.getChild(0))

    override fun visitAtribuicao(ctx: PlarParser.AtribuicaoContext): Value {
        ctx.logicaOu()?.let { return visit(it) }
        val rhs = when {
            ctx.expressao() != null -> visit(ctx.expressao())
            else -> throw SemanticError("Atribuicao invalida")
        }
        val id = ctx.ID()
        val acess = ctx.acesso()
        val arr = ctx.acessoArray()
        return when {
            id != null -> rhs.also { v ->
                environment.updateOrDefine(id.text, v)
            }

            acess != null -> {
                val obj = visit(acess.primario()) as? Value.Object
                    ?: throw SemanticError("Nao é possível atribuir a uma propriedade de um nao objeto")
                obj.fields[acess.ID().text] = rhs
                rhs
            }

            arr != null -> {
                when (val container = visit(arr.primario())) {
                    is Value.List -> {
                        val i = visit(arr.expressao(0)) as? Value.Integer
                            ?: throw SemanticError("Indice de lista deve ser um numero inteiro")
                        val index = i.value
                        if (index < 0) throw SemanticError("Indice negativo nao permitido: $index")
                        container.elements[index] = rhs
                        rhs
                    }

                    //TODO: fix map index
                    is Value.Map -> {
                        val chave = visit(arr.expressao(0))
                        container.elements[chave] = rhs
                        rhs
                    }

                    else -> throw SemanticError(
                        "Operaçao de atribuiçao com indice nao suportada para ${container::class.simpleName}"
                    )
                }
            }

            else -> throw SemanticError("Erro de sintaxe na atribuicao")
        }
    }

    override fun visitAcesso(ctx: PlarParser.AcessoContext): Value {
        val obj = visit(ctx.primario())

        if (obj !is Value.Object) {
            throw SemanticError("Tentativa de acessar propriedade de um nao objeto")
        }

        val property = ctx.ID().text

        val value = obj.fields[property] ?: return Value.Null
        return value
    }

    override fun visitLogicaOu(ctx: PlarParser.LogicaOuContext): Value {
        var left = visit(ctx.logicaE(0))
        for (i in 1 until ctx.logicaE().size) {
            if (left is Value.Logic && left.value) return Value.Logic(true)
            val right = visit(ctx.logicaE(i))
            if (left !is Value.Logic || right !is Value.Logic) throw SemanticError("Operador 'ou' requer valores lógicos")
            left = Value.Logic(right.value)
        }
        return left
    }

    override fun visitLogicaE(ctx: PlarParser.LogicaEContext): Value {
        var left = visit(ctx.igualdade(0))
        for (i in 1 until ctx.igualdade().size) {
            if (left is Value.Logic && !left.value) return Value.Logic(false)
            val right = visit(ctx.igualdade(i))
            if (left !is Value.Logic || right !is Value.Logic) throw SemanticError("Operador 'e' requer valores lógicos")
            left = Value.Logic(right.value)
        }
        return left
    }

    override fun visitIgualdade(ctx: PlarParser.IgualdadeContext): Value {
        var left = visit(ctx.comparacao(0))

        for (i in 1 until ctx.comparacao().size) {
            val operator = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.comparacao(i))

            if (operator == "==") {
                val result = when {
                    left == Value.Null && right == Value.Null -> true
                    left == Value.Null || right == Value.Null -> false
                    else -> areEqual(left, right)
                }
                left = Value.Logic(result)
            } else if (operator == "!=") {
                val resultado = when {
                    left == Value.Null && right == Value.Null -> false
                    left == Value.Null || right == Value.Null -> true
                    else -> !areEqual(left, right)
                }
                left = Value.Logic(resultado)
            }
        }

        return left
    }

    override fun visitComparacao(ctx: PlarParser.ComparacaoContext): Value {
        var left = visit(ctx.adicao(0))
        for (i in 1 until ctx.adicao().size) {
            val operator = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.adicao(i))
            left = when (operator) {
                "<" -> compare("<", left, right)
                "<=" -> compare("<=", left, right)
                ">" -> compare(">", left, right)
                ">=" -> compare(">=", left, right)
                else -> throw SemanticError("Operador desconhecido: $operator")
            }
        }
        return left
    }


    override fun visitAdicao(ctx: PlarParser.AdicaoContext): Value {
        var left = visit(ctx.multiplicacao(0))
        for (i in 1 until ctx.multiplicacao().size) {
            val operator = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.multiplicacao(i))
            left = processAdd(operator, left, right)
        }
        return left
    }

    override fun visitMultiplicacao(ctx: PlarParser.MultiplicacaoContext): Value {
        var left = visit(ctx.unario(0))
        for (i in 1 until ctx.unario().size) {
            val operator = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.unario(i))
            left = processMultiplication(operator, left, right)
        }
        return left
    }

    override fun visitUnario(ctx: PlarParser.UnarioContext): Value {
        if (ctx.childCount == 2) {
            val operator = ctx.getChild(0).text
            val operand = visit(ctx.unario())
            return when (operator) {
                "!" -> if (operand is Value.Logic) Value.Logic(!operand.value) else throw SemanticError("Operador '!' requer valor logico")
                "-" -> when (operand) {
                    is Value.Integer -> Value.Integer(-operand.value)
                    is Value.Real -> Value.Real(-operand.value)
                    else -> throw SemanticError("Operador '-' requer valor numerico")
                }

                else -> throw SemanticError("Operador unario desconhecido: $operator")
            }
        }
        return visit(ctx.getChild(0))
    }

    private fun findPropertyInHierarch(`object`: Value.Object, fieldName: String): Value? {
        val fieldValue = `object`.fields[fieldName]
        if (fieldValue != null) {
            return fieldValue
        }

        if (`object`.superClass != null) {
            val tempObj = createTempClassObject(`object`.superClass)
            return findPropertyInHierarch(tempObj, fieldName)
        }

        return null
    }


    private fun createTempClassObject(className: String): Value.Object {
        val `class` = global.getClass(className) ?: throw SemanticError("Classe nao encontrada: $className")

        val superClass = global.getSuperClasse(`class`)
        val interfaces = global.getInterfaces(`class`)

        val `object` = Value.Object(className, mutableMapOf(), superClass, interfaces)

        `class`.declaracaoVar().forEach { decl ->
            val fieldName = decl.ID().text
            val value = decl.expressao()?.let {
                val oldEnv = environment
                environment = Environment(global).apply { thisObject = `object` }
                val result = visit(it)
                environment = oldEnv
                result
            } ?: Value.Null
            `object`.fields[fieldName] = value
        }

        return `object`
    }

    private fun findMethodInHierarchy(`object`: Value.Object, methodName: String): PlarParser.DeclaracaoFuncaoContext? {
        val `class` = global.getClass(`object`.klass) ?: return null
        val method = `class`.declaracaoFuncao().find { it.ID().text == methodName }
        if (method != null) return method
        if (`object`.superClass != null) {
            val baseClass = global.getClass(`object`.superClass) ?: return null
            val baseMethod = baseClass.declaracaoFuncao().find { it.ID().text == methodName }
            if (baseMethod != null)
                return baseMethod
            val baseSuperClass = global.getSuperClasse(baseClass)
            if (baseSuperClass != null) {
                val objectBase = Value.Object(`object`.superClass, mutableMapOf(), baseSuperClass)
                return findMethodInHierarchy(objectBase, methodName)
            }
        }
        return null
    }


    private fun isCall(ctx: PlarParser.ChamadaContext, i: Int, n: Int) = (i + 2) < n && ctx.getChild(i + 2).text == "("

    private fun extractArgumentsAndIndex(ctx: PlarParser.ChamadaContext, i: Int, n: Int): Pair<List<Value>, Int> {
        val temArgsCtx = (i + 3) < n && ctx.getChild(i + 3) is PlarParser.ArgumentosContext
        val arguments = if (temArgsCtx) {
            val argsCtx = ctx.getChild(i + 3) as PlarParser.ArgumentosContext
            argsCtx.expressao().map { visit(it) }
        } else emptyList()
        val step = if (temArgsCtx) 5 else 4
        return arguments to step
    }

    private fun callMethodOrError(obj: Value.Object, nome: String, args: List<Value>): Value {
        val method = findMethodInHierarchy(obj, nome)
            ?: throw SemanticError("Metodo nao encontrado: $nome em classe ${obj.klass}")
        return executeMethod(obj, method, args)
    }

    private fun readProperty(obj: Value.Object, name: String): Value? = findPropertyInHierarch(obj, name)

    override fun visitChamada(ctx: PlarParser.ChamadaContext): Value {
        ctx.acessoArray()?.let { return visit(it) }

        var r = visit(ctx.primario())
        var i = 1
        val n = ctx.childCount

        while (i < n) {
            if (r == Value.Null) return Value.Null
            if (!isDot(ctx, i)) break

            val id = ctx.getChild(i + 1).text
            val obj = asObjectOrError(r)

            if (isCall(ctx, i, n)) {
                val (args, index) = extractArgumentsAndIndex(ctx, i, n)
                r = callMethodOrError(obj, id, args)
                i += index
            } else {
                r = readProperty(obj, id) ?: Value.Null
                i += 2
            }
        }
        return r
    }

    override fun visitDeclaracaoEnquanto(ctx: PlarParser.DeclaracaoEnquantoContext): Value {
        var iterationsNum = 0
        while (iterationsNum < LOOP.MAX_LOOP.valor) {
            val condicao = visit(ctx.expressao())
            println("Condicao do loop: $condicao")
            if (condicao !is Value.Logic) throw SemanticError("Condicao do 'enquanto' deve ser um valor logico")
            if (!condicao.value) {
                println("Condicao falsa, saindo do loop")
                break
            }

            iterationsNum++
            println("Iteracao $iterationsNum do loop")

            try {
                visit(ctx.declaracao())
            } catch (e: RetornoException) {
                throw e
            } catch (_: BreakException) {
                break
            } catch (_: ContinueException) {
                continue
            }
        }

        if (iterationsNum >= LOOP.MAX_LOOP.valor) {
            println("Aviso: Loop infinito detectado! Saindo do loop.")
            return Value.Null
        }
        return Value.Null
    }

    override fun visitDeclaracaoPara(ctx: PlarParser.DeclaracaoParaContext): Value {
        ctx.declaracaoVar()?.let { visit(it) } ?: ctx.expressao(0)?.let { visit(it) }
        loop@ while (true) {
            val cond = visit(ctx.expressao(0)) as? Value.Logic
                ?: throw SemanticError("Condicao do 'para' deve ser um valor logico")
            if (!cond.value) break

            var doIncrement = true
            try {
                visit(ctx.declaracao())
            } catch (e: Exception) {
                when (e) {
                    is RetornoException -> throw e
                    is BreakException -> {
                        doIncrement = false; break@loop
                    }

                    is ContinueException -> {}
                    else -> throw e
                }
            } finally {
                if (doIncrement) {
                    visit(ctx.expressao(1))
                }
            }
        }
        return Value.Null
    }

    override fun visitDeclaracaoFacaEnquanto(ctx: PlarParser.DeclaracaoFacaEnquantoContext): Value {
        var iter = 0
        do {
            try {
                visit(ctx.declaracao())
            } catch (_: BreakException) {
                break
            } catch (_: ContinueException) {
                // only jumps...
            }
            val c = visit(ctx.expressao())
            val logicRes =
                (c as? Value.Logic)?.value ?: throw SemanticError("Condicao do 'enquanto' deve ser um valor logico")
            if (!logicRes) break
            if (++iter >= 100) {
                println("Loop infinito detectado! Saindo do loop.")
                break
            }
        } while (true)

        return Value.Null
    }

    override fun visitDeclaracaoQuebra(ctx: PlarParser.DeclaracaoQuebraContext): Value {
        throw BreakException()
    }

    //TODO: ajustar declaracao de listas....
    override fun visitListaLiteral(ctx: PlarParser.ListaLiteralContext): Value {
        val index = ctx.NUMERO().text.toInt()
        val list = mutableListOf<Value>()
        while (list.size < index) list.add(Value.Null)
        return Value.List(list, index)
    }


    override fun visitMapaLiteral(ctx: PlarParser.MapaLiteralContext): Value {
        return Value.Map()
    }

    private fun validarAcessoArray(ctx: PlarParser.AcessoArrayContext, container: Value.List): Value {
        val index = visit(ctx.expressao(0))
        if (index !is Value.Integer) throw SemanticError("Indice de lista deve ser um numero inteiro")
        if (index.value < 0 || index.value >= container.size) throw SemanticError("Indice fora dos limites da lista: ${index.value}")
        return container.elements[index.value]
    }

    private fun validarAcessoMapa(ctx: PlarParser.AcessoArrayContext, container: Value.Map): Value {
        val key = visit(ctx.expressao(0))

        // Para acesso bidimensional em mapas
        if (ctx.expressao().size > 1) {
            val firstElement = container.elements[key] ?: Value.Null
            val secondIndex = visit(ctx.expressao(1))

            when (firstElement) {
                is Value.List -> {
                    when {
                        secondIndex !is Value.Integer -> {
                            throw SemanticError("Segundo indice deve ser um número inteiro para acessar uma lista")
                        }

                        secondIndex.value < 0 || secondIndex.value >= firstElement.elements.size -> {
                            throw SemanticError("Segundo indice fora dos limites da lista: ${secondIndex.value}")
                        }

                        else -> return firstElement.elements[secondIndex.value]
                    }
                }
                //TODO: refactor map case
                is Value.Map -> {
                    return firstElement.elements[secondIndex] ?: Value.Null
                }
                // TODO: refactor object case
                is Value.Object -> {
                    if (secondIndex !is Value.Text) {
                        throw SemanticError("Chave para acessar campo de objeto deve ser texto")
                    }
                    return firstElement.fields[secondIndex.value] ?: Value.Null
                }

                else -> {
                    throw SemanticError("Elemento com chave $key nao suporta acesso indexado")
                }
            }
        }
        return container.elements[key] ?: Value.Null
    }

    override fun visitAcessoArray(ctx: PlarParser.AcessoArrayContext): Value {
        return when (val container = visit(ctx.primario())) {
            is Value.List -> validarAcessoArray(ctx, container)
            is Value.Map -> validarAcessoMapa(ctx, container)
            else -> throw SemanticError("Operacao de acesso com indice nao suportada para ${container::class.simpleName}")
        }
    }

    override fun visitDeclaracaoContinue(ctx: PlarParser.DeclaracaoContinueContext): Value {
        throw ContinueException()
    }

    override fun visitChamadaFuncao(ctx: PlarParser.ChamadaFuncaoContext): Value {
        //TODO: implement validation for all parameters
        val args = ctx.argumentos()?.expressao()?.map { visit(it) } ?: emptyList()
        val funcName = ctx.ID().text
        return if (ctx.primario() != null) {
            val obj = visit(ctx.primario())
            if (obj !is Value.Object) throw SemanticError("Chamada de metodo em nao objeto")
            val classe = global.getClass(obj.klass) ?: throw SemanticError("Classe nao encontrada: ${obj.klass}")
            val method = classe.declaracaoFuncao().find { it.ID().text == funcName }
                ?: throw SemanticError("Metodo nao encontrado: $funcName")
            executeMethod(obj, method, args)
        } else {
            functionCall(funcName, args)
        }
    }

    private fun solveFunction(name: String): Value.Fun = runCatching { environment.get(name) as? Value.Fun }.getOrNull()
        ?: throw SemanticError("Funcao nao encontrada ou nao seria funcao: $name")

    private fun functionCall(nome: String, args: List<Value>): Value {
        environment.thisObject?.let { obj ->
            findMethodInHierarchy(obj, nome)?.let { ctx ->
                return executeMethod(
                    obj, ctx, args
                )
            }
        }
        val function = solveFunction(nome)
        return function.implementation?.invoke(args) ?: throw SemanticError("Funcao '$nome' nao possui implementacao.")
    }


    private fun executeMethod(
        `object`: Value.Object, method: PlarParser.DeclaracaoFuncaoContext, arguments: List<Value>
    ): Value {
        val envMethod = Environment(global)
        envMethod.thisObject = `object`

        val `fun` = Value.Fun(
            method.ID().text, method, method.tipo()?.text, envMethod
        )
        val previousFunction = actualFunction
        actualFunction = `fun`

        val params = method.listaParams()?.param() ?: listOf()
        for (i in params.indices) {
            val paramNome = params[i].ID().text

            if (i < arguments.size) {
                val argVal = arguments[i]
                envMethod.define(paramNome, argVal)
            } else {
                envMethod.define(paramNome, Value.Null)
            }
        }

        val oldAmbiente = environment
        environment = envMethod

        try {
            visit(method.bloco())
            return Value.Null
        } catch (err: RetornoException) {
            return err.value
        } finally {
            environment = oldAmbiente
            actualFunction = previousFunction
        }
    }

    private fun solveId(ctx: PlarParser.PrimarioContext): Value {
        val name = ctx.ID().text
        if (ctx.childCount > 1 && ctx.getChild(1).text == "(") {
            val arguments = if (ctx.childCount > 2 && ctx.getChild(2) is PlarParser.ArgumentosContext) {
                val argsCtx = ctx.getChild(2) as PlarParser.ArgumentosContext
                argsCtx.expressao().map { visit(it) }
            } else {
                emptyList()
            }
            return functionCall(name, arguments)
        } else {
            try {
                return environment.get(name)
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private fun solveClass(ctx: PlarParser.PrimarioContext): Value {
        val match = Regex("novo([A-Za-z0-9_]+)\\(.*\\)").find(ctx.text)
        if (match != null) {
            val className = match.groupValues[1]

            val `class` = global.getClass(className) ?: throw SemanticError("Classe nao encontrada: $className")
            return createClassObject(className, ctx, `class`)
        } else {
            throw SemanticError("Sintaxe invalida para criacao de objeto")
        }
    }


    override fun visitPrimario(ctx: PlarParser.PrimarioContext): Value {
        return when {
            ctx.text == "nulo" -> Value.Null
            ctx.listaLiteral() != null -> visit(ctx.listaLiteral())
            ctx.mapaLiteral() != null -> visit(ctx.mapaLiteral())
            ctx.NUMERO() != null -> ctx.NUMERO().text.let {
                if (it.contains(".")) Value.Real(it.toDouble()) else Value.Integer(
                    it.toInt()
                )
            }

            ctx.TEXTO_LITERAL() != null -> Value.Text(ctx.TEXTO_LITERAL().text.removeSurrounding("\""))
            ctx.ID() != null && !ctx.text.startsWith("novo") -> solveId(ctx);
            ctx.text == "verdadeiro" -> Value.Logic(true)
            ctx.text == "falso" -> Value.Logic(false)
            ctx.text == "este" -> environment.thisObject ?: throw SemanticError("'este' fora de contexto de objeto")
            ctx.text.startsWith("novo") -> solveClass(ctx)
            ctx.expressao() != null -> visit(ctx.expressao())
            else -> {
                throw SemanticError("Tipo primario invalido")
            }
        }
    }

    //TODO: do more tests on this function....
    private fun extractConstructorArgs(ctx: PlarParser.PrimarioContext): List<Value> {
        val args = mutableListOf<Value>()
        if (!ctx.argumentos().isEmpty) {
            ctx.argumentos().expressao().forEach { expr ->
                args.add(visit(expr))
            }
        }
        return args
    }

    //TODO: remove recursion.
    private fun initializeBaseClassFields(`object`: Value.Object, baseClassName: String) {
        val baseClass = global.getClass(baseClassName) ?: return

        val superBaseClass = global.getSuperClasse(baseClass)
        if (superBaseClass != null) {
            initializeBaseClassFields(`object`, superBaseClass)
        }

        baseClass.declaracaoVar().forEach { decl ->
            val fieldName = decl.ID().text
            if (!`object`.fields.containsKey(fieldName)) {
                val oldEnv = environment
                environment = Environment(global).apply { thisObject = `object` }
                val value = decl.expressao()?.let { visit(it) } ?: Value.Null
                `object`.fields[fieldName] = value
                environment = oldEnv
            }
        }
    }

    private fun createClassObject(
        nomeClasse: String, ctx: PlarParser.PrimarioContext, classe: PlarParser.DeclaracaoClasseContext
    ): Value {
        val superClass = global.getSuperClasse(classe)
        val interfaces = global.getInterfaces(classe)

        val `object` = Value.Object(nomeClasse, mutableMapOf(), superClass, interfaces)

        if (superClass != null) initializeBaseClassFields(`object`, superClass)

        classe.declaracaoVar().forEach { decl ->
            val fieldName = decl.ID().text
            val oldEnv = environment
            environment = Environment(global).apply { thisObject = `object` }
            val value = decl.expressao()?.let { visit(it) } ?: Value.Null
            `object`.fields[fieldName] = value
            environment = oldEnv
        }

        val initializeMethod = classe.declaracaoFuncao().find { it.ID().text == "inicializar" }
        if (initializeMethod != null) {
            val argumentos = extractConstructorArgs(ctx)
            executeMethod(`object`, initializeMethod, argumentos)
        }
        return `object`
    }
}
