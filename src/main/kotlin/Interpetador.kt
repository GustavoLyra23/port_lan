package org.gustavolyra.portugolpp

import ehPonto
import helpers.getAbsolutePath
import models.Ambiente
import models.Valor
import models.enums.LOOP
import models.errors.*
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.gustavolyra.portugolpp.PortugolPPParser.*
import processors.*
import java.nio.file.Files


@Suppress("REDUNDANT_OVERRIDE")
class Interpretador : PortugolPPBaseVisitor<Valor>() {
    /** Ambiente global que contém todas as definições de classes, interfaces e funções globais */
    private var global = Ambiente()

    /** Ambiente atual de execução, pode ser o global ou um escopo local */
    private var ambiente = global

    /** Referência para a função atualmente em execução (usado para verificação de tipos de retorno) */
    private var funcaoAtual: Valor.Funcao? = null

    private val arquivosImportados = mutableSetOf<String>()

    //setando funcoes nativas da linguagem...
    init {
        setFuncoesDefault(global)
    }

    override fun visitImportarDeclaracao(ctx: ImportarDeclaracaoContext): Valor {
        val nomeArquivo = ctx.TEXTO_LITERAL().text.removeSurrounding("\"")
        processarImport(nomeArquivo)
        return Valor.Nulo
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


    fun processarImport(nomeArquivo: String) {
        if (arquivosImportados.contains(nomeArquivo)) return
        arquivosImportados.add(nomeArquivo)

        try {
            val path = getAbsolutePath(nomeArquivo)
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

    fun interpretar(tree: ProgramaContext) {
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

    override fun visitDeclaracaoInterface(ctx: DeclaracaoInterfaceContext): Valor {
        val nomeInterface = ctx.ID().text
        global.definirInterface(nomeInterface, ctx)
        return Valor.Nulo
    }

    override fun visitDeclaracaoTentarCapturar(ctx: DeclaracaoTentarCapturarContext?): Valor? {
        try {
            visit(ctx?.bloco(0))
        } catch (_: Exception) {
            visit(ctx?.bloco(1))
        }
        return Valor.Nulo
    }

    override fun visitDeclaracaoClasse(ctx: DeclaracaoClasseContext): Valor {
        val nomeClasse = ctx.ID(0).text

        obterSuperclasseSeHouver(ctx)?.let { sc ->
            validarSuperclasseExiste(sc, nomeClasse, global)
        }
        indiceDaPalavra(ctx, "implementa").takeIf { it >= 0 }?.let { idx ->
            val interfaces = lerIdentificadoresAteChave(ctx, idx + 1)
            validarInterfacesOuErro(ctx, nomeClasse, interfaces, global)
        }
        global.definirClasse(nomeClasse, ctx)
        return Valor.Nulo
    }

    override fun visitDeclaracaoVar(ctx: DeclaracaoVarContext): Valor {
        val nome = ctx.ID().text
        val tipo = ctx.tipo()?.text
        val valor = when {
            (ctx.expressao() != null) -> visit(ctx.expressao());
            else -> visit(ctx.declaracaoFuncao())
        }

        if (tipo != null) {
            if (valor is Valor.Objeto) {
                val nomeClasse = valor.klass
                if (tipo != nomeClasse && valor.superClasse != tipo && !valor.interfaces.contains(tipo)) throw SemanticError(
                    "Tipo de variável '$tipo' não corresponde ao tipo do objeto '$nomeClasse'"
                )
            } else {
                if (tipo != valor.typeString()) throw SemanticError("Tipo da variavel nao corresponde ao tipo correto atribuido.")
            }
        }
        ambiente.definir(nome, valor)
        return Valor.Nulo
    }

    override fun visitDeclaracaoFuncao(ctx: DeclaracaoFuncaoContext): Valor {
        val nome = ctx.ID().text
        val tipoRetorno = ctx.tipo()?.text
        if (retornoFuncaoInvalido(tipoRetorno, global)) throw SemanticError("Tipo de retorno inválido: $tipoRetorno")
        val func = Valor.Funcao(
            nome = nome,
            declaracao = ctx,
            tipoRetorno = tipoRetorno,
            closure = ambiente,
            implementacao = definirImplementacao(ctx, nome, Ambiente(ambiente))
        )
        ambiente.definir(nome, func)
        return func
    }

    private fun definirImplementacao(
        ctx: DeclaracaoFuncaoContext, nome: String, closure: Ambiente
    ): (List<Valor>) -> Valor {
        return { argumentos ->
            val numParamsDeclarados = ctx.listaParams()?.param()?.size ?: 0
            if (argumentos.size > numParamsDeclarados) throw SemanticError("Função '$nome' recebeu ${argumentos.size} parâmetros, mas espera $numParamsDeclarados")
            ctx.listaParams()?.param()?.forEachIndexed { i, param ->
                if (i < argumentos.size) closure.definir(param.ID().text, argumentos[i])
            }

            val ambienteAnterior = ambiente
            ambiente = closure
            val funcao = Valor.Funcao(
                ctx.ID().text, ctx, ctx.tipo()?.text, global
            )
            val funcaoAnterior = funcaoAtual
            funcaoAtual = funcao
            try {
                visit(ctx.bloco())
                Valor.Nulo
            } catch (retorno: RetornoException) {
                retorno.valor
            } finally {
                ambiente = ambienteAnterior
                funcaoAtual = funcaoAnterior
            }
        }
    }

    //TODO: refatorar vist para declaracao de return
    override fun visitDeclaracaoRetornar(ctx: DeclaracaoRetornarContext): Valor {
        val valorRetorno = ctx.expressao()?.let { visit(it) } ?: Valor.Nulo
        // apenas valida se estivermos dentro de uma funcao
        if (funcaoAtual != null && funcaoAtual!!.tipoRetorno != null) {
            val tipoEsperado = funcaoAtual!!.tipoRetorno
            val tipoAtual = valorRetorno.typeString()
            if (tipoEsperado != tipoAtual) {
                if (valorRetorno is Valor.Objeto) {
                    //TODO: colocar verificao de superclasses e interfaces...
                    if (valorRetorno.superClasse == tipoEsperado || valorRetorno.interfaces.contains(tipoEsperado)) throw RetornoException(
                        valorRetorno
                    )
                }
                throw SemanticError("Erro de tipo: funcao '${funcaoAtual!!.nome}' deve retornar '$tipoEsperado', mas esta retornando '$tipoAtual'")
            }
        }
        throw RetornoException(valorRetorno)
    }

    override fun visitDeclaracaoSe(ctx: DeclaracaoSeContext): Valor {
        val condicao = visit(ctx.expressao())
        if (condicao !is Valor.Logico) throw SemanticError("Condição do 'if' deve ser lógica")
        return if (condicao.valor) visit(ctx.declaracao(0)) else ctx.declaracao(1)?.let { visit(it) } ?: Valor.Nulo
    }

    override fun visitBloco(ctx: BlocoContext): Valor {
        val anterior = ambiente
        ambiente = Ambiente(anterior)
        ambiente.thisObjeto = anterior.thisObjeto
        try {
            ctx.declaracao().forEach { visit(it) }
        } finally {
            ambiente = anterior
        }
        return Valor.Nulo
    }

    override fun visitExpressao(ctx: ExpressaoContext): Valor = visit(ctx.getChild(0))

    override fun visitAtribuicao(ctx: AtribuicaoContext): Valor {
        ctx.logicaOu()?.let { return visit(it) }
        val rhs = when {
            ctx.expressao() != null -> visit(ctx.expressao())
            else -> throw SemanticError("Atribuicao invalida")
        }
        val id = ctx.ID()
        val acesso = ctx.acesso()
        val arr = ctx.acessoArray()
        return when {
            id != null -> rhs.also { v ->
                ambiente.atualizarOuDefinir(id.text, v)
            }

            acesso != null -> {
                val obj = visit(acesso.primario()) as? Valor.Objeto
                    ?: throw SemanticError("Não é possível atribuir a uma propriedade de um não-objeto")
                val v = rhs
                obj.campos[acesso.ID().text] = v
                v
            }

            arr != null -> {
                when (val container = visit(arr.primario())) {
                    is Valor.Lista -> {
                        val i = visit(arr.expressao(0)) as? Valor.Inteiro
                            ?: throw SemanticError("Índice de lista deve ser um número inteiro")
                        val indice = i.valor
                        if (indice < 0) throw SemanticError("Índice negativo não permitido: $indice")
                        container.elementos[indice] = rhs
                        rhs
                    }

                    //TODO: arrumar indices nos mapas...
                    is Valor.Mapa -> {
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

    override fun visitAcesso(ctx: AcessoContext): Valor {
        val objeto = visit(ctx.primario())

        if (objeto !is Valor.Objeto) {
            throw SemanticError("Tentativa de acessar propriedade de um não-objeto")
        }

        val propriedade = ctx.ID().text

        val valor = objeto.campos[propriedade] ?: return Valor.Nulo
        return valor
    }

    override fun visitLogicaOu(ctx: LogicaOuContext): Valor {
        var esquerda = visit(ctx.logicaE(0))
        for (i in 1 until ctx.logicaE().size) {
            if (esquerda is Valor.Logico && esquerda.valor) return Valor.Logico(true)
            val direita = visit(ctx.logicaE(i))
            if (esquerda !is Valor.Logico || direita !is Valor.Logico) throw SemanticError("Operador 'ou' requer valores lógicos")
            esquerda = Valor.Logico(direita.valor)
        }
        return esquerda
    }

    override fun visitLogicaE(ctx: LogicaEContext): Valor {
        var esquerda = visit(ctx.igualdade(0))
        for (i in 1 until ctx.igualdade().size) {
            if (esquerda is Valor.Logico && !esquerda.valor) return Valor.Logico(false)
            val direita = visit(ctx.igualdade(i))
            if (esquerda !is Valor.Logico || direita !is Valor.Logico) throw SemanticError("Operador 'e' requer valores lógicos")
            esquerda = Valor.Logico(direita.valor)
        }
        return esquerda
    }

    override fun visitIgualdade(ctx: IgualdadeContext): Valor {
        var esquerda = visit(ctx.comparacao(0))

        for (i in 1 until ctx.comparacao().size) {
            val operador = ctx.getChild(i * 2 - 1).text
            val direita = visit(ctx.comparacao(i))

            if (operador == "==") {
                val resultado = when {
                    esquerda == Valor.Nulo && direita == Valor.Nulo -> true
                    esquerda == Valor.Nulo || direita == Valor.Nulo -> false
                    else -> saoIguais(esquerda, direita)
                }
                esquerda = Valor.Logico(resultado)
            } else if (operador == "!=") {
                val resultado = when {
                    esquerda == Valor.Nulo && direita == Valor.Nulo -> false
                    esquerda == Valor.Nulo || direita == Valor.Nulo -> true
                    else -> !saoIguais(esquerda, direita)
                }
                esquerda = Valor.Logico(resultado)
            }
        }

        return esquerda
    }

    override fun visitComparacao(ctx: ComparacaoContext): Valor {
        var esquerda = visit(ctx.adicao(0))
        for (i in 1 until ctx.adicao().size) {
            val operador = ctx.getChild(i * 2 - 1).text
            val direita = visit(ctx.adicao(i))
            esquerda = when (operador) {
                "<" -> comparar("<", esquerda, direita)
                "<=" -> comparar("<=", esquerda, direita)
                ">" -> comparar(">", esquerda, direita)
                ">=" -> comparar(">=", esquerda, direita)
                else -> throw SemanticError("Operador desconhecido: $operador")
            }
        }
        return esquerda
    }


    override fun visitAdicao(ctx: AdicaoContext): Valor {
        var esquerda = visit(ctx.multiplicacao(0))
        for (i in 1 until ctx.multiplicacao().size) {
            val operador = ctx.getChild(i * 2 - 1).text
            val direita = visit(ctx.multiplicacao(i))
            esquerda = processarAdicao(operador, esquerda, direita)
        }
        return esquerda
    }

    override fun visitMultiplicacao(ctx: MultiplicacaoContext): Valor {
        var esquerda = visit(ctx.unario(0))
        for (i in 1 until ctx.unario().size) {
            val operador = ctx.getChild(i * 2 - 1).text
            val direita = visit(ctx.unario(i))
            esquerda = processarMultiplicacao(operador, esquerda, direita)
        }
        return esquerda
    }

    override fun visitUnario(ctx: UnarioContext): Valor {
        if (ctx.childCount == 2) {
            val operador = ctx.getChild(0).text
            val operando = visit(ctx.unario())
            return when (operador) {
                "!" -> if (operando is Valor.Logico) Valor.Logico(!operando.valor) else throw SemanticError("Operador '!' requer valor lógico")
                "-" -> when (operando) {
                    is Valor.Inteiro -> Valor.Inteiro(-operando.valor)
                    is Valor.Real -> Valor.Real(-operando.valor)
                    else -> throw SemanticError("Operador '-' requer valor numérico")
                }

                else -> throw SemanticError("Operador unário desconhecido: $operador")
            }
        }
        return visit(ctx.getChild(0))
    }

    private fun buscarPropriedadeNaHierarquia(objeto: Valor.Objeto, nomeCampo: String): Valor? {
        val valorCampo = objeto.campos[nomeCampo]
        if (valorCampo != null) {
            return valorCampo
        }

        if (objeto.superClasse != null) {
            val tempObjeto = criarObjetoTemporarioDaClasse(objeto.superClasse)
            return buscarPropriedadeNaHierarquia(tempObjeto, nomeCampo)
        }

        return null
    }


    private fun criarObjetoTemporarioDaClasse(nomeClasse: String): Valor.Objeto {
        val classe = global.obterClasse(nomeClasse) ?: throw SemanticError("Classe não encontrada: $nomeClasse")

        val superClasse = global.getSuperClasse(classe)
        val interfaces = global.getInterfaces(classe)

        val objeto = Valor.Objeto(nomeClasse, mutableMapOf(), superClasse, interfaces)

        classe.declaracaoVar().forEach { decl ->
            val nomeCampo = decl.ID().text
            val valor = decl.expressao()?.let {
                val oldAmbiente = ambiente
                ambiente = Ambiente(global).apply { thisObjeto = objeto }
                val result = visit(it)
                ambiente = oldAmbiente
                result
            } ?: Valor.Nulo
            objeto.campos[nomeCampo] = valor
        }

        return objeto
    }

    private fun buscarMetodoNaHierarquia(objeto: Valor.Objeto, nomeMetodo: String): DeclaracaoFuncaoContext? {
        val classe = global.obterClasse(objeto.klass) ?: return null
        val metodo = classe.declaracaoFuncao().find { it.ID().text == nomeMetodo }
        if (metodo != null) return metodo
        if (objeto.superClasse != null) {
            val classeBase = global.obterClasse(objeto.superClasse) ?: return null
            val metodoBase = classeBase.declaracaoFuncao().find { it.ID().text == nomeMetodo }
            if (metodoBase != null) {
                return metodoBase
            }
            val superClasseDaBase = global.getSuperClasse(classeBase)
            if (superClasseDaBase != null) {
                val objetoBase = Valor.Objeto(objeto.superClasse, mutableMapOf(), superClasseDaBase)
                return buscarMetodoNaHierarquia(objetoBase, nomeMetodo)
            }
        }
        return null
    }


    private fun ehChamada(ctx: ChamadaContext, i: Int, n: Int) = (i + 2) < n && ctx.getChild(i + 2).text == "("

    private fun extrairArgumentosEPasso(ctx: ChamadaContext, i: Int, n: Int): Pair<List<Valor>, Int> {
        val temArgsCtx = (i + 3) < n && ctx.getChild(i + 3) is ArgumentosContext
        val argumentos = if (temArgsCtx) {
            val argsCtx = ctx.getChild(i + 3) as ArgumentosContext
            argsCtx.expressao().map { visit(it) }
        } else emptyList()
        val passo = if (temArgsCtx) 5 else 4
        return argumentos to passo
    }

    private fun chamarMetodoOuErro(obj: Valor.Objeto, nome: String, argumentos: List<Valor>): Valor {
        val metodo = buscarMetodoNaHierarquia(obj, nome)
            ?: throw SemanticError("Metodo nao encontrado: $nome em classe ${obj.klass}")
        return executarMetodo(obj, metodo, argumentos)
    }

    private fun lerPropriedadeOuNulo(obj: Valor.Objeto, nome: String): Valor? = buscarPropriedadeNaHierarquia(obj, nome)

    override fun visitChamada(ctx: ChamadaContext): Valor {
        ctx.acessoArray()?.let { return visit(it) }

        var r = visit(ctx.primario())
        var i = 1
        val n = ctx.childCount

        while (i < n) {
            if (r == Valor.Nulo) return Valor.Nulo
            if (!ehPonto(ctx, i)) break

            val id = ctx.getChild(i + 1).text
            val obj = comoObjetoOuErro(r)

            if (ehChamada(ctx, i, n)) {
                val (argumentos, passo) = extrairArgumentosEPasso(ctx, i, n)
                r = chamarMetodoOuErro(obj, id, argumentos)
                i += passo
            } else {
                r = lerPropriedadeOuNulo(obj, id) ?: Valor.Nulo
                i += 2
            }
        }
        return r
    }

    override fun visitDeclaracaoEnquanto(ctx: DeclaracaoEnquantoContext): Valor {
        var iteracoes = 0
        val maxIteracoes = LOOP.VALOR_MAX_LOOP.valor

        while (iteracoes < maxIteracoes) {
            val condicao = visit(ctx.expressao())
            println("Condição do loop: $condicao")

            if (condicao !is Valor.Logico) {
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
            return Valor.Nulo
        }
        return Valor.Nulo
    }

    override fun visitDeclaracaoPara(ctx: DeclaracaoParaContext): Valor {
        ctx.declaracaoVar()?.let { visit(it) } ?: ctx.expressao(0)?.let { visit(it) }
        loop@ while (true) {
            val cond = visit(ctx.expressao(0)) as? Valor.Logico
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
        return Valor.Nulo
    }

    override fun visitDeclaracaoFacaEnquanto(ctx: DeclaracaoFacaEnquantoContext): Valor {
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
                (c as? Valor.Logico)?.valor ?: throw SemanticError("Condição do 'enquanto' deve ser um valor lógico")
            if (!logicRes) break
            if (++iter >= 100) {
                println("Loop infinito detectado! Saindo do loop.")
                break
            }
        } while (true)

        return Valor.Nulo
    }

    override fun visitDeclaracaoQuebra(ctx: DeclaracaoQuebraContext): Valor {
        throw BreakException()
    }

    //TODO: ajustar declaracao de listas....
    override fun visitListaLiteral(ctx: ListaLiteralContext): Valor {
        val indice = ctx.NUMERO().text.toInt()
        val list = mutableListOf<Valor>()
        while (list.size < indice) list.add(Valor.Nulo)
        return Valor.Lista(list, indice)
    }


    override fun visitMapaLiteral(ctx: MapaLiteralContext): Valor {
        return Valor.Mapa()
    }

    private fun validarAcessoArray(ctx: AcessoArrayContext, container: Valor.Lista): Valor {
        val indice = visit(ctx.expressao(0))
        if (indice !is Valor.Inteiro) throw SemanticError("Índice de lista deve ser um número inteiro")
        if (indice.valor < 0 || indice.valor >= container.tamanho) throw SemanticError("Índice fora dos limites da lista: ${indice.valor}")
        return container.elementos[indice.valor]
    }

    private fun validarAcessoMapa(ctx: AcessoArrayContext, container: Valor.Mapa): Valor {
        val chave = visit(ctx.expressao(0))

        // Para acesso bidimensional em mapas
        if (ctx.expressao().size > 1) {
            val primeiroElemento = container.elementos[chave] ?: Valor.Nulo
            val segundoIndice = visit(ctx.expressao(1))

            when (primeiroElemento) {
                is Valor.Lista -> {
                    when {
                        segundoIndice !is Valor.Inteiro -> {
                            throw SemanticError("Segundo índice deve ser um número inteiro para acessar uma lista")
                        }

                        segundoIndice.valor < 0 || segundoIndice.valor >= primeiroElemento.elementos.size -> {
                            throw SemanticError("Segundo índice fora dos limites da lista: ${segundoIndice.valor}")
                        }

                        else -> return primeiroElemento.elementos[segundoIndice.valor]
                    }
                }
                //TODO: rever mapa case
                is Valor.Mapa -> {
                    return primeiroElemento.elementos[segundoIndice] ?: Valor.Nulo
                }
                // TODO: rever objeto case
                is Valor.Objeto -> {
                    if (segundoIndice !is Valor.Texto) {
                        throw SemanticError("Chave para acessar campo de objeto deve ser texto")
                    }
                    return primeiroElemento.campos[segundoIndice.valor] ?: Valor.Nulo
                }

                else -> {
                    throw SemanticError("Elemento com chave $chave não suporta acesso indexado")
                }
            }
        }
        return container.elementos[chave] ?: Valor.Nulo
    }

    override fun visitAcessoArray(ctx: AcessoArrayContext): Valor {
        return when (val container = visit(ctx.primario())) {
            is Valor.Lista -> validarAcessoArray(ctx, container)
            is Valor.Mapa -> validarAcessoMapa(ctx, container)
            else -> throw SemanticError("Operação de acesso com índice não suportada para ${container::class.simpleName}")
        }
    }

    override fun visitDeclaracaoContinue(ctx: DeclaracaoContinueContext): Valor {
        throw ContinueException()
    }

    override fun visitChamadaFuncao(ctx: ChamadaFuncaoContext): Valor {
        //TODO: implementar validacao dos tipos dos parametros
        val argumentos = ctx.argumentos()?.expressao()?.map { visit(it) } ?: emptyList()
        val funcName = ctx.ID().text
        return if (ctx.primario() != null) {
            val objeto = visit(ctx.primario())
            if (objeto !is Valor.Objeto) throw SemanticError("Chamada de método em não-objeto")
            val classe =
                global.obterClasse(objeto.klass) ?: throw SemanticError("Classe não encontrada: ${objeto.klass}")
            val metodo = classe.declaracaoFuncao().find { it.ID().text == funcName }
                ?: throw SemanticError("Método não encontrado: $funcName")
            executarMetodo(objeto, metodo, argumentos)
        } else {
            chamadaFuncao(funcName, argumentos)
        }
    }

    private fun resolverFuncao(nome: String): Valor.Funcao =
        runCatching { ambiente.obter(nome) as? Valor.Funcao }.getOrNull()
            ?: throw SemanticError("Função não encontrada ou não é função: $nome")

    private fun chamadaFuncao(nome: String, argumentos: List<Valor>): Valor {
        ambiente.thisObjeto?.let { obj ->
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


    private fun executarMetodo(objeto: Valor.Objeto, metodo: DeclaracaoFuncaoContext, argumentos: List<Valor>): Valor {
        val metodoAmbiente = Ambiente(global)
        metodoAmbiente.thisObjeto = objeto

        val funcao = Valor.Funcao(
            metodo.ID().text, metodo, metodo.tipo()?.text, metodoAmbiente
        )
        val funcaoAnterior = funcaoAtual
        funcaoAtual = funcao

        val params = metodo.listaParams()?.param() ?: listOf()
        for (i in params.indices) {
            val paramNome = params[i].ID().text

            if (i < argumentos.size) {
                val valorArg = argumentos[i]
                metodoAmbiente.definir(paramNome, valorArg)
            } else {
                metodoAmbiente.definir(paramNome, Valor.Nulo)
            }
        }

        val oldAmbiente = ambiente
        ambiente = metodoAmbiente

        try {
            visit(metodo.bloco())
            return Valor.Nulo
        } catch (retorno: RetornoException) {
            return retorno.valor
        } finally {
            ambiente = oldAmbiente
            funcaoAtual = funcaoAnterior
        }
    }

    private fun resolverIdPrimario(ctx: PrimarioContext): Valor {
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
                return ambiente.obter(nome)
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private fun resolverClassePrimario(ctx: PrimarioContext): Valor {
        val match = Regex("novo([A-Za-z0-9_]+)\\(.*\\)").find(ctx.text)
        if (match != null) {
            val nomeClasse = match.groupValues[1]

            val classe = global.obterClasse(nomeClasse) ?: throw SemanticError("Classe não encontrada: $nomeClasse")
            return criarObjetoClasse(nomeClasse, ctx, classe)
        } else {
            throw SemanticError("Sintaxe inválida para criação de objeto")
        }
    }


    override fun visitPrimario(ctx: PrimarioContext): Valor {
        return when {
            ctx.text == "nulo" -> Valor.Nulo
            ctx.listaLiteral() != null -> visit(ctx.listaLiteral())
            ctx.mapaLiteral() != null -> visit(ctx.mapaLiteral())
            ctx.NUMERO() != null -> ctx.NUMERO().text.let {
                if (it.contains(".")) Valor.Real(it.toDouble()) else Valor.Inteiro(
                    it.toInt()
                )
            }

            ctx.TEXTO_LITERAL() != null -> Valor.Texto(ctx.TEXTO_LITERAL().text.removeSurrounding("\""))
            ctx.ID() != null && !ctx.text.startsWith("novo") -> resolverIdPrimario(ctx);
            ctx.text == "verdadeiro" -> Valor.Logico(true)
            ctx.text == "falso" -> Valor.Logico(false)
            ctx.text == "este" -> ambiente.thisObjeto ?: throw SemanticError("'este' fora de contexto de objeto")
            ctx.text.startsWith("novo") -> resolverClassePrimario(ctx)
            ctx.expressao() != null -> visit(ctx.expressao())
            else -> {
                throw SemanticError("Tipo primario invalido")
            }
        }
    }

    //TODO: testar mais essa funcao de extracao argumentos do constructor..., ja testei com tipos simples e com objetos
    private fun extrairArgumentosDoConstructor(ctx: PrimarioContext): List<Valor> {
        val args = mutableListOf<Valor>()
        if (!ctx.argumentos().isEmpty) {
            ctx.argumentos().expressao().forEach { expr ->
                args.add(visit(expr))
            }
        }
        return args
    }

    //TODO: rever uso de recursao...
    private fun inicializarCamposDaClasseBase(objeto: Valor.Objeto, nomeClasseBase: String) {
        val classeBase = global.obterClasse(nomeClasseBase) ?: return

        val superClasseDaBase = global.getSuperClasse(classeBase)
        if (superClasseDaBase != null) {
            inicializarCamposDaClasseBase(objeto, superClasseDaBase)
        }

        classeBase.declaracaoVar().forEach { decl ->
            val nomeCampo = decl.ID().text
            if (!objeto.campos.containsKey(nomeCampo)) {
                val oldAmbiente = ambiente
                ambiente = Ambiente(global).apply { thisObjeto = objeto }
                val valor = decl.expressao()?.let { visit(it) } ?: Valor.Nulo
                objeto.campos[nomeCampo] = valor
                ambiente = oldAmbiente
            }
        }
    }

    private fun criarObjetoClasse(nomeClasse: String, ctx: PrimarioContext, classe: DeclaracaoClasseContext): Valor {
        val superClasse = global.getSuperClasse(classe)
        val interfaces = global.getInterfaces(classe)

        val objeto = Valor.Objeto(nomeClasse, mutableMapOf(), superClasse, interfaces)

        if (superClasse != null) inicializarCamposDaClasseBase(objeto, superClasse)

        classe.declaracaoVar().forEach { decl ->
            val nomeCampo = decl.ID().text
            val oldAmbiente = ambiente
            ambiente = Ambiente(global).apply { thisObjeto = objeto }
            val valor = decl.expressao()?.let { visit(it) } ?: Valor.Nulo
            objeto.campos[nomeCampo] = valor
            ambiente = oldAmbiente
        }

        val inicializarMetodo = classe.declaracaoFuncao().find { it.ID().text == "inicializar" }
        if (inicializarMetodo != null) {
            val argumentos = extrairArgumentosDoConstructor(ctx)
            executarMetodo(objeto, inicializarMetodo, argumentos)
        }
        return objeto
    }
}
