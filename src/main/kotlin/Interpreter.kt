package org.gustavolyra.portugolpp

import helpers.solvePath
import isDot
import models.Environment
import models.Value
import models.enums.LOOP
import models.errors.*
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.gustavolyra.portugolpp.PortugolPPParser.*
import processors.*
import java.nio.file.Files


@Suppress("REDUNDANT_OVERRIDE")
class Interpreter : PortugolPPBaseVisitor<Value>() {
    private var global = Environment()

    // actual exec environment
    private var environment = global

    // ref to the function being executed
    private var actualFunction: Value.Funcao? = null

    private val importedModules = mutableSetOf<String>()

    init {
        defineDefaultFunctions(global)
    }

    override fun visitImportarDeclaracao(ctx: ImportarDeclaracaoContext): Value {
        val nomeArquivo = ctx.TEXTO_LITERAL().text.removeSurrounding("\"")
        processarImport(nomeArquivo)
        return Value.Null
    }

    private fun processarDeclaracoesDoArquivo(tree: ProgramaContext) {
        tree.declaracao().forEach { declaracao ->
            declaracao.declaracaoInterface()?.let {
                visitDeclaracaoInterface(it)
            }
        }

        tree.declaracao()?.forEach { declaracao ->
            declaracao.declaracaoClasse()?.let {
                visitDeclaracaoClasse(it)
            }
        }

        tree.declaracao()?.forEach { declaracao ->
            declaracao.declaracaoFuncao()?.let {
                visitDeclaracaoFuncao(it)
            }
        }

        tree.declaracao()?.forEach { declaracao ->
            declaracao.declaracaoVar()?.let {
                visitDeclaracaoVar(it)
            }
        }
    }


    fun processarImport(filName: String) {
        if (importedModules.contains(filName)) return
        importedModules.add(filName)
        try {
            val path = solvePath(filName)
            val conteudo = Files.readString(path)
            val lexer = PortugolPPLexer(CharStreams.fromString(conteudo))
            val tokens = CommonTokenStream(lexer)
            val parser = PortugolPPParser(tokens)
            val arvore = parser.programa()

            arvore.importarDeclaracao().forEach { import ->
                visitImportarDeclaracao(import)
            }

            processarDeclaracoesDoArquivo(arvore)
        } catch (e: Exception) {
            throw ArquivoException(e.message ?: "Falha ao processar import")
        }
    }

    fun interpret(tree: ProgramaContext) {
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

    override fun visitDeclaracaoInterface(ctx: DeclaracaoInterfaceContext): Value {
        val interfaceName = ctx.ID().text
        global.setInterface(interfaceName, ctx)
        return Value.Null
    }

    override fun visitDeclaracaoTentarCapturar(ctx: DeclaracaoTentarCapturarContext?): Value? {
        try {
            visit(ctx?.bloco(0))
        } catch (_: Exception) {
            visit(ctx?.bloco(1))
        }
        return Value.Null
    }

    override fun visitDeclaracaoClasse(ctx: DeclaracaoClasseContext): Value {
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

    override fun visitDeclaracaoVar(ctx: DeclaracaoVarContext): Value {
        val name = ctx.ID().text
        val type = ctx.tipo()?.text
        val value = when {
            (ctx.expressao() != null) -> visit(ctx.expressao());
            else -> visit(ctx.declaracaoFuncao())
        }

        if (type != null) {
            if (value is Value.Object) {
                val nomeClasse = value.klass
                if (type != nomeClasse && value.superClasse != type && !value.interfaces.contains(type)) throw SemanticError(
                    "Tipo de variável '$type' não corresponde ao tipo do objeto '$nomeClasse'"
                )
            } else {
                if (type != value.typeString()) throw SemanticError("Tipo da variavel nao corresponde ao tipo correto atribuido.")
            }
        }
        environment.define(name, value)
        return Value.Null
    }

    override fun visitDeclaracaoFuncao(ctx: DeclaracaoFuncaoContext): Value {
        val name = ctx.ID().text
        val returnType = ctx.tipo()?.text
        if (isReturnInvalid(returnType, global)) throw SemanticError("Tipo de retorno inválido: $returnType")
        val func = Value.Funcao(
            nome = name,
            declaracao = ctx,
            tipoRetorno = returnType,
            closure = environment,
            implementacao = definirImplementacao(ctx, name, Environment(environment))
        )
        environment.define(name, func)
        return func
    }

    private fun definirImplementacao(
        ctx: DeclaracaoFuncaoContext, name: String, closure: Environment
    ): (List<Value>) -> Value {
        return { argumentos ->
            val declaredParameters = ctx.listaParams()?.param()?.size ?: 0
            if (argumentos.size > declaredParameters) throw SemanticError("Função '$name' recebeu ${argumentos.size} parâmetros, mas espera $declaredParameters")
            ctx.listaParams()?.param()?.forEachIndexed { i, param ->
                if (i < argumentos.size) closure.define(param.ID().text, argumentos[i])
            }

            val actualEnviromnent = environment
            environment = closure
            val funcao = Value.Funcao(
                ctx.ID().text, ctx, ctx.tipo()?.text, global
            )
            val oldFunction = actualFunction
            actualFunction = funcao
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

    //TODO: refatorar vist para declaracao de return
    override fun visitDeclaracaoRetornar(ctx: DeclaracaoRetornarContext): Value {
        val returnVal = ctx.expressao()?.let { visit(it) } ?: Value.Null
        // apenas valida se estivermos dentro de uma funcao
        if (actualFunction != null && actualFunction!!.tipoRetorno != null) {
            val expectedType = actualFunction!!.tipoRetorno
            val actualType = returnVal.typeString()
            if (expectedType != actualType) {
                if (returnVal is Value.Object) {
                    //TODO: colocar verificao de superclasses e interfaces...
                    if (returnVal.superClasse == expectedType || returnVal.interfaces.contains(expectedType)) throw RetornoException(
                        returnVal
                    )
                }
                throw SemanticError("Erro de tipo: funcao '${actualFunction!!.nome}' deve retornar '$expectedType', mas esta retornando '$actualType'")
            }
        }
        throw RetornoException(returnVal)
    }

    override fun visitDeclaracaoSe(ctx: DeclaracaoSeContext): Value {
        val condition = visit(ctx.expressao())
        if (condition !is Value.Logico) throw SemanticError("Condição do 'if' deve ser lógica")
        return if (condition.valor) visit(ctx.declaracao(0)) else ctx.declaracao(1)?.let { visit(it) } ?: Value.Null
    }

    override fun visitBloco(ctx: BlocoContext): Value {
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

    override fun visitExpressao(ctx: ExpressaoContext): Value = visit(ctx.getChild(0))

    override fun visitAtribuicao(ctx: AtribuicaoContext): Value {
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
                    ?: throw SemanticError("Não é possível atribuir a uma propriedade de um não-objeto")
                val v = rhs
                obj.campos[acess.ID().text] = v
                v
            }

            arr != null -> {
                when (val container = visit(arr.primario())) {
                    is Value.List -> {
                        val i = visit(arr.expressao(0)) as? Value.Integer
                            ?: throw SemanticError("Índice de lista deve ser um número inteiro")
                        val index = i.valor
                        if (index < 0) throw SemanticError("Índice negativo não permitido: $index")
                        container.elementos[index] = rhs
                        rhs
                    }

                    //TODO: arrumar indices nos mapas...
                    is Value.Map -> {
                        val chave = visit(arr.expressao(0))
                        container.elementos[chave] = rhs
                        rhs
                    }

                    else -> throw SemanticError(
                        "Operação de atribuição com índice não suportada para ${container::class.simpleName}"
                    )
                }
            }

            else -> throw SemanticError("Erro de sintaxe na atribuição")
        }
    }

    override fun visitAcesso(ctx: AcessoContext): Value {
        val objeto = visit(ctx.primario())

        if (objeto !is Value.Object) {
            throw SemanticError("Tentativa de acessar propriedade de um não-objeto")
        }

        val property = ctx.ID().text

        val value = objeto.campos[property] ?: return Value.Null
        return value
    }

    override fun visitLogicaOu(ctx: LogicaOuContext): Value {
        var left = visit(ctx.logicaE(0))
        for (i in 1 until ctx.logicaE().size) {
            if (left is Value.Logico && left.valor) return Value.Logico(true)
            val right = visit(ctx.logicaE(i))
            if (left !is Value.Logico || right !is Value.Logico) throw SemanticError("Operador 'ou' requer valores lógicos")
            left = Value.Logico(right.valor)
        }
        return left
    }

    override fun visitLogicaE(ctx: LogicaEContext): Value {
        var left = visit(ctx.igualdade(0))
        for (i in 1 until ctx.igualdade().size) {
            if (left is Value.Logico && !left.valor) return Value.Logico(false)
            val right = visit(ctx.igualdade(i))
            if (left !is Value.Logico || right !is Value.Logico) throw SemanticError("Operador 'e' requer valores lógicos")
            left = Value.Logico(right.valor)
        }
        return left
    }

    override fun visitIgualdade(ctx: IgualdadeContext): Value {
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
                left = Value.Logico(result)
            } else if (operator == "!=") {
                val resultado = when {
                    left == Value.Null && right == Value.Null -> false
                    left == Value.Null || right == Value.Null -> true
                    else -> !areEqual(left, right)
                }
                left = Value.Logico(resultado)
            }
        }

        return left
    }

    override fun visitComparacao(ctx: ComparacaoContext): Value {
        var left = visit(ctx.adicao(0))
        for (i in 1 until ctx.adicao().size) {
            val operador = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.adicao(i))
            left = when (operador) {
                "<" -> compare("<", left, right)
                "<=" -> compare("<=", left, right)
                ">" -> compare(">", left, right)
                ">=" -> compare(">=", left, right)
                else -> throw SemanticError("Operador desconhecido: $operador")
            }
        }
        return left
    }


    override fun visitAdicao(ctx: AdicaoContext): Value {
        var left = visit(ctx.multiplicacao(0))
        for (i in 1 until ctx.multiplicacao().size) {
            val operador = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.multiplicacao(i))
            left = processAdd(operador, left, right)
        }
        return left
    }

    override fun visitMultiplicacao(ctx: MultiplicacaoContext): Value {
        var left = visit(ctx.unario(0))
        for (i in 1 until ctx.unario().size) {
            val operador = ctx.getChild(i * 2 - 1).text
            val right = visit(ctx.unario(i))
            left = processMultiplication(operador, left, right)
        }
        return left
    }

    override fun visitUnario(ctx: UnarioContext): Value {
        if (ctx.childCount == 2) {
            val operator = ctx.getChild(0).text
            val operando = visit(ctx.unario())
            return when (operator) {
                "!" -> if (operando is Value.Logico) Value.Logico(!operando.valor) else throw SemanticError("Operador '!' requer valor lógico")
                "-" -> when (operando) {
                    is Value.Integer -> Value.Integer(-operando.valor)
                    is Value.Real -> Value.Real(-operando.valor)
                    else -> throw SemanticError("Operador '-' requer valor numérico")
                }

                else -> throw SemanticError("Operador unário desconhecido: $operator")
            }
        }
        return visit(ctx.getChild(0))
    }

    private fun buscarPropriedadeNaHierarquia(`object`: Value.Object, fieldName: String): Value? {
        val fieldValue = `object`.campos[fieldName]
        if (fieldValue != null) {
            return fieldValue
        }

        if (`object`.superClasse != null) {
            val tempObjeto = criarObjetoTemporarioDaClasse(`object`.superClasse)
            return buscarPropriedadeNaHierarquia(tempObjeto, fieldName)
        }

        return null
    }


    private fun criarObjetoTemporarioDaClasse(nomeClasse: String): Value.Object {
        val classe = global.getClass(nomeClasse) ?: throw SemanticError("Classe não encontrada: $nomeClasse")

        val superClasse = global.getSuperClasse(classe)
        val interfaces = global.getInterfaces(classe)

        val `object` = Value.Object(nomeClasse, mutableMapOf(), superClasse, interfaces)

        classe.declaracaoVar().forEach { decl ->
            val nomeCampo = decl.ID().text
            val value = decl.expressao()?.let {
                val oldAmbiente = environment
                environment = Environment(global).apply { thisObject = `object` }
                val result = visit(it)
                environment = oldAmbiente
                result
            } ?: Value.Null
            `object`.campos[nomeCampo] = value
        }

        return `object`
    }

    private fun buscarMetodoNaHierarquia(`object`: Value.Object, nomeMetodo: String): DeclaracaoFuncaoContext? {
        val classe = global.getClass(`object`.klass) ?: return null
        val metodo = classe.declaracaoFuncao().find { it.ID().text == nomeMetodo }
        if (metodo != null) return metodo
        if (`object`.superClasse != null) {
            val classeBase = global.getClass(`object`.superClasse) ?: return null
            val metodoBase = classeBase.declaracaoFuncao().find { it.ID().text == nomeMetodo }
            if (metodoBase != null) {
                return metodoBase
            }
            val superClasseDaBase = global.getSuperClasse(classeBase)
            if (superClasseDaBase != null) {
                val objectBase = Value.Object(`object`.superClasse, mutableMapOf(), superClasseDaBase)
                return buscarMetodoNaHierarquia(objectBase, nomeMetodo)
            }
        }
        return null
    }


    private fun isCall(ctx: ChamadaContext, i: Int, n: Int) = (i + 2) < n && ctx.getChild(i + 2).text == "("

    private fun extrairArgumentosEPasso(ctx: ChamadaContext, i: Int, n: Int): Pair<List<Value>, Int> {
        val temArgsCtx = (i + 3) < n && ctx.getChild(i + 3) is ArgumentosContext
        val argumentos = if (temArgsCtx) {
            val argsCtx = ctx.getChild(i + 3) as ArgumentosContext
            argsCtx.expressao().map { visit(it) }
        } else emptyList()
        val passo = if (temArgsCtx) 5 else 4
        return argumentos to passo
    }

    private fun chamarMetodoOuErro(obj: Value.Object, nome: String, argumentos: List<Value>): Value {
        val metodo = buscarMetodoNaHierarquia(obj, nome)
            ?: throw SemanticError("Metodo nao encontrado: $nome em classe ${obj.klass}")
        return executarMetodo(obj, metodo, argumentos)
    }

    private fun lerPropriedadeOuNulo(obj: Value.Object, nome: String): Value? = buscarPropriedadeNaHierarquia(obj, nome)

    override fun visitChamada(ctx: ChamadaContext): Value {
        ctx.acessoArray()?.let { return visit(it) }

        var r = visit(ctx.primario())
        var i = 1
        val n = ctx.childCount

        while (i < n) {
            if (r == Value.Null) return Value.Null
            if (!isDot(ctx, i)) break

            val id = ctx.getChild(i + 1).text
            val obj = comoObjetoOuErro(r)

            if (isCall(ctx, i, n)) {
                val (argumentos, passo) = extrairArgumentosEPasso(ctx, i, n)
                r = chamarMetodoOuErro(obj, id, argumentos)
                i += passo
            } else {
                r = lerPropriedadeOuNulo(obj, id) ?: Value.Null
                i += 2
            }
        }
        return r
    }

    override fun visitDeclaracaoEnquanto(ctx: DeclaracaoEnquantoContext): Value {
        var iteracoes = 0
        val maxIteracoes = LOOP.MAX_LOOP.valor

        while (iteracoes < maxIteracoes) {
            val condicao = visit(ctx.expressao())
            println("Condição do loop: $condicao")

            if (condicao !is Value.Logico) {
                throw SemanticError("Condição do 'enquanto' deve ser um valor lógico")
            }

            if (!condicao.valor) {
                println("Condição falsa, saindo do loop")
                break
            }

            iteracoes++
            println("Iteração $iteracoes do loop")

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

        if (iteracoes >= maxIteracoes) {
            println("Aviso: Loop infinito detectado! Saindo do loop.")
            return Value.Null
        }
        return Value.Null
    }

    override fun visitDeclaracaoPara(ctx: DeclaracaoParaContext): Value {
        ctx.declaracaoVar()?.let { visit(it) } ?: ctx.expressao(0)?.let { visit(it) }
        loop@ while (true) {
            val cond = visit(ctx.expressao(0)) as? Value.Logico
                ?: throw SemanticError("Condição do 'para' deve ser um valor lógico")
            if (!cond.valor) break

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

    override fun visitDeclaracaoFacaEnquanto(ctx: DeclaracaoFacaEnquantoContext): Value {
        var iter = 0
        do {
            try {
                visit(ctx.declaracao())
            } catch (_: BreakException) {
                break
            } catch (_: ContinueException) {
                // apenas pula...
            }
            val c = visit(ctx.expressao())
            val logicRes =
                (c as? Value.Logico)?.valor ?: throw SemanticError("Condição do 'enquanto' deve ser um valor lógico")
            if (!logicRes) break
            if (++iter >= 100) {
                println("Loop infinito detectado! Saindo do loop.")
                break
            }
        } while (true)

        return Value.Null
    }

    override fun visitDeclaracaoQuebra(ctx: DeclaracaoQuebraContext): Value {
        throw BreakException()
    }

    //TODO: ajustar declaracao de listas....
    override fun visitListaLiteral(ctx: ListaLiteralContext): Value {
        val indice = ctx.NUMERO().text.toInt()
        val list = mutableListOf<Value>()
        while (list.size < indice) list.add(Value.Null)
        return Value.List(list, indice)
    }


    override fun visitMapaLiteral(ctx: MapaLiteralContext): Value {
        return Value.Map()
    }

    private fun validarAcessoArray(ctx: AcessoArrayContext, container: Value.List): Value {
        val indice = visit(ctx.expressao(0))
        if (indice !is Value.Integer) throw SemanticError("Índice de lista deve ser um número inteiro")
        if (indice.valor < 0 || indice.valor >= container.tamanho) throw SemanticError("Índice fora dos limites da lista: ${indice.valor}")
        return container.elementos[indice.valor]
    }

    private fun validarAcessoMapa(ctx: AcessoArrayContext, container: Value.Map): Value {
        val chave = visit(ctx.expressao(0))

        // Para acesso bidimensional em mapas
        if (ctx.expressao().size > 1) {
            val primeiroElemento = container.elementos[chave] ?: Value.Null
            val segundoIndice = visit(ctx.expressao(1))

            when (primeiroElemento) {
                is Value.List -> {
                    when {
                        segundoIndice !is Value.Integer -> {
                            throw SemanticError("Segundo índice deve ser um número inteiro para acessar uma lista")
                        }

                        segundoIndice.valor < 0 || segundoIndice.valor >= primeiroElemento.elementos.size -> {
                            throw SemanticError("Segundo índice fora dos limites da lista: ${segundoIndice.valor}")
                        }

                        else -> return primeiroElemento.elementos[segundoIndice.valor]
                    }
                }
                //TODO: rever mapa case
                is Value.Map -> {
                    return primeiroElemento.elementos[segundoIndice] ?: Value.Null
                }
                // TODO: rever objeto case
                is Value.Object -> {
                    if (segundoIndice !is Value.Text) {
                        throw SemanticError("Chave para acessar campo de objeto deve ser texto")
                    }
                    return primeiroElemento.campos[segundoIndice.valor] ?: Value.Null
                }

                else -> {
                    throw SemanticError("Elemento com chave $chave não suporta acesso indexado")
                }
            }
        }
        return container.elementos[chave] ?: Value.Null
    }

    override fun visitAcessoArray(ctx: AcessoArrayContext): Value {
        return when (val container = visit(ctx.primario())) {
            is Value.List -> validarAcessoArray(ctx, container)
            is Value.Map -> validarAcessoMapa(ctx, container)
            else -> throw SemanticError("Operação de acesso com índice não suportada para ${container::class.simpleName}")
        }
    }

    override fun visitDeclaracaoContinue(ctx: DeclaracaoContinueContext): Value {
        throw ContinueException()
    }

    override fun visitChamadaFuncao(ctx: ChamadaFuncaoContext): Value {
        //TODO: implementar validacao dos tipos dos parametros
        val argumentos = ctx.argumentos()?.expressao()?.map { visit(it) } ?: emptyList()
        val funcName = ctx.ID().text
        return if (ctx.primario() != null) {
            val objeto = visit(ctx.primario())
            if (objeto !is Value.Object) throw SemanticError("Chamada de método em não-objeto")
            val classe =
                global.getClass(objeto.klass) ?: throw SemanticError("Classe não encontrada: ${objeto.klass}")
            val metodo = classe.declaracaoFuncao().find { it.ID().text == funcName }
                ?: throw SemanticError("Método não encontrado: $funcName")
            executarMetodo(objeto, metodo, argumentos)
        } else {
            chamadaFuncao(funcName, argumentos)
        }
    }

    private fun resolverFuncao(nome: String): Value.Funcao =
        runCatching { environment.get(nome) as? Value.Funcao }.getOrNull()
            ?: throw SemanticError("Função não encontrada ou não é função: $nome")

    private fun chamadaFuncao(nome: String, argumentos: List<Value>): Value {
        environment.thisObject?.let { obj ->
            buscarMetodoNaHierarquia(obj, nome)?.let { ctx ->
                return executarMetodo(
                    obj, ctx, argumentos
                )
            }
        }
        val funcao = resolverFuncao(nome)
        return funcao.implementacao?.invoke(argumentos)
            ?: throw SemanticError("Função '$nome' não possui implementação.")
    }


    private fun executarMetodo(
        `object`: Value.Object,
        metodo: DeclaracaoFuncaoContext,
        argumentos: List<Value>
    ): Value {
        val metodoEnvironment = Environment(global)
        metodoEnvironment.thisObject = `object`

        val funcao = Value.Funcao(
            metodo.ID().text, metodo, metodo.tipo()?.text, metodoEnvironment
        )
        val funcaoAnterior = actualFunction
        actualFunction = funcao

        val params = metodo.listaParams()?.param() ?: listOf()
        for (i in params.indices) {
            val paramNome = params[i].ID().text

            if (i < argumentos.size) {
                val valorArg = argumentos[i]
                metodoEnvironment.define(paramNome, valorArg)
            } else {
                metodoEnvironment.define(paramNome, Value.Null)
            }
        }

        val oldAmbiente = environment
        environment = metodoEnvironment

        try {
            visit(metodo.bloco())
            return Value.Null
        } catch (retorno: RetornoException) {
            return retorno.value
        } finally {
            environment = oldAmbiente
            actualFunction = funcaoAnterior
        }
    }

    private fun resolverIdPrimario(ctx: PrimarioContext): Value {
        val nome = ctx.ID().text
        if (ctx.childCount > 1 && ctx.getChild(1).text == "(") {
            val argumentos = if (ctx.childCount > 2 && ctx.getChild(2) is ArgumentosContext) {
                val argsCtx = ctx.getChild(2) as ArgumentosContext
                argsCtx.expressao().map { visit(it) }
            } else {
                emptyList()
            }
            return chamadaFuncao(nome, argumentos)
        } else {
            try {
                return environment.get(nome)
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private fun resolverClassePrimario(ctx: PrimarioContext): Value {
        val match = Regex("novo([A-Za-z0-9_]+)\\(.*\\)").find(ctx.text)
        if (match != null) {
            val nomeClasse = match.groupValues[1]

            val classe = global.getClass(nomeClasse) ?: throw SemanticError("Classe não encontrada: $nomeClasse")
            return criarObjetoClasse(nomeClasse, ctx, classe)
        } else {
            throw SemanticError("Sintaxe inválida para criação de objeto")
        }
    }


    override fun visitPrimario(ctx: PrimarioContext): Value {
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
            ctx.ID() != null && !ctx.text.startsWith("novo") -> resolverIdPrimario(ctx);
            ctx.text == "verdadeiro" -> Value.Logico(true)
            ctx.text == "falso" -> Value.Logico(false)
            ctx.text == "este" -> environment.thisObject ?: throw SemanticError("'este' fora de contexto de objeto")
            ctx.text.startsWith("novo") -> resolverClassePrimario(ctx)
            ctx.expressao() != null -> visit(ctx.expressao())
            else -> {
                throw SemanticError("Tipo primario invalido")
            }
        }
    }

    //TODO: testar mais essa funcao de extracao argumentos do constructor..., ja testei com tipos simples e com objetos
    private fun extrairArgumentosDoConstructor(ctx: PrimarioContext): List<Value> {
        val args = mutableListOf<Value>()
        if (!ctx.argumentos().isEmpty) {
            ctx.argumentos().expressao().forEach { expr ->
                args.add(visit(expr))
            }
        }
        return args
    }

    //TODO: rever uso de recursao...
    private fun inicializarCamposDaClasseBase(`object`: Value.Object, nomeClasseBase: String) {
        val classeBase = global.getClass(nomeClasseBase) ?: return

        val superClasseDaBase = global.getSuperClasse(classeBase)
        if (superClasseDaBase != null) {
            inicializarCamposDaClasseBase(`object`, superClasseDaBase)
        }

        classeBase.declaracaoVar().forEach { decl ->
            val nomeCampo = decl.ID().text
            if (!`object`.campos.containsKey(nomeCampo)) {
                val oldAmbiente = environment
                environment = Environment(global).apply { thisObject = `object` }
                val value = decl.expressao()?.let { visit(it) } ?: Value.Null
                `object`.campos[nomeCampo] = value
                environment = oldAmbiente
            }
        }
    }

    private fun criarObjetoClasse(nomeClasse: String, ctx: PrimarioContext, classe: DeclaracaoClasseContext): Value {
        val superClasse = global.getSuperClasse(classe)
        val interfaces = global.getInterfaces(classe)

        val `object` = Value.Object(nomeClasse, mutableMapOf(), superClasse, interfaces)

        if (superClasse != null) inicializarCamposDaClasseBase(`object`, superClasse)

        classe.declaracaoVar().forEach { decl ->
            val nomeCampo = decl.ID().text
            val oldAmbiente = environment
            environment = Environment(global).apply { thisObject = `object` }
            val value = decl.expressao()?.let { visit(it) } ?: Value.Null
            `object`.campos[nomeCampo] = value
            environment = oldAmbiente
        }

        val inicializarMetodo = classe.declaracaoFuncao().find { it.ID().text == "inicializar" }
        if (inicializarMetodo != null) {
            val argumentos = extrairArgumentosDoConstructor(ctx)
            executarMetodo(`object`, inicializarMetodo, argumentos)
        }
        return `object`
    }
}
