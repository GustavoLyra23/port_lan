package processors

import extractValueToPrint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import models.Ambiente
import models.Valor
import models.errors.ArquivoException
import processors.file_io_processor.escreverArquivo
import processors.file_io_processor.lerArquivo
import java.util.*

fun setFuncoesDefault(global: Ambiente) {
    registrarFuncoesIO(global)
    registarFuncoesThread(global)
    registrarFuncoesExecptions(global)
    registrarFuncoesColecoes(global)
}

fun registrarFuncoesIO(global: Ambiente) {
    global.definir("ler", Valor.Funcao("ler", null, "Texto", global) { args ->
        Scanner(System.`in`).nextLine().let { Valor.Texto(it) }
    })
    global.definir("lerArquivo", Valor.Funcao("lerArquivo", null, "Texto", global) { args ->
        if (args.isEmpty()) throw RuntimeException("Função lerArquivo requer um argumento (caminho do arquivo)")
        if (args.size > 1) throw RuntimeException("Função lerArquivo aceita apenas um argumento")

        val argVal = args[0]
        if (argVal !is Valor.Texto) {
            throw RuntimeException("Argumento deve ser um texto (caminho do arquivo)")
        }

        try {
            Valor.Texto(lerArquivo(argVal.valor))
        } catch (e: Exception) {
            throw ArquivoException("Erro ao ler arquivo '${argVal.valor}': ${e.message}")
        }
    })
    global.definir("escreverArquivo", Valor.Funcao("escreverArquivo", null, null, global) { args ->
        require(args.size in 2..3) { "Função escreverArquivo requer 2 ou 3 argumentos" }
        val (path, data) = args.take(2)
        val append = args.getOrNull(2)

        require(path is Valor.Texto && data is Valor.Texto) {
            "Os dois primeiros argumentos devem ser do tipo Texto"
        }
        when (append) {
            null -> escreverArquivo(path.valor, data.valor)
            is Valor.Logico -> escreverArquivo(
                path.valor, data.valor, append.valor
            )

            else -> throw RuntimeException("O terceiro argumento deve ser do tipo Logico")
        }
        Valor.Nulo
    })
    global.definir("escrever", Valor.Funcao("escrever", null, null, global) { args ->
        val valores = args.map { extractValueToPrint(it) }
        println(valores.joinToString(" "))
        Valor.Nulo
    })
    global.definir("imprimir", Valor.Funcao("imprimir", null, null, global) { args ->
        val valores = args.map { extractValueToPrint(it) }
        println(valores.joinToString(" "))
        Valor.Nulo
    })
}

fun registarFuncoesThread(global: Ambiente) {
    global.definir("executar", Valor.Funcao("executar", null, null, global) { args ->
        if (args.isEmpty() || args[0] !is Valor.Funcao) throw RuntimeException("Argumento invalido para a funcao.")
        val funcaoParaExecutar = args[0] as Valor.Funcao
        val argumentosReais = args.drop(1)
        //run sincrono...
        runBlocking {
            launch {
                try {
                    funcaoParaExecutar.implementacao!!.invoke(argumentosReais)
                } catch (e: Exception) {
                    println("Erro na execucao da thread: ${e.message}")
                }
            }.join()
        }
        Valor.Nulo
    })
    global.definir("dormir", Valor.Funcao("aguardar", null, null, global) { args ->
        if (args.isEmpty()) throw RuntimeException("Função aguardar requer um argumento (milissegundos)")
        val tempo = args[0]
        if (tempo !is Valor.Inteiro) throw RuntimeException("Argumento deve ser um número inteiro (milissegundos)")
        runBlocking {
            delay(tempo.valor.toLong())
        }
        Valor.Nulo
    })
}

fun registrarFuncoesExecptions(global: Ambiente) {
    global.definir("jogarError", Valor.Funcao("jogarError", null, null, global) { args ->
        if (args.isEmpty()) {
            throw RuntimeException("Função jogarError requer um argumento (mensagem de erro)")
        }
        val mensagem = args[0]
        if (mensagem !is Valor.Texto) {
            throw RuntimeException("Argumento deve ser um texto (mensagem de erro)")
        }
        throw RuntimeException(mensagem.valor)
    })
}

fun registrarFuncoesColecoes(global: Ambiente) {
    global.definir("tamanho", Valor.Funcao("tamanho", null, "Inteiro", global) { args ->
        if (args.isEmpty()) {
            throw RuntimeException("Função tamanho requer um argumento (lista, mapa ou texto)")
        }

        when (val arg = args[0]) {
            is Valor.Lista -> Valor.Inteiro(arg.elementos.size)
            is Valor.Mapa -> Valor.Inteiro(arg.elementos.size)
            is Valor.Texto -> Valor.Inteiro(arg.valor.length)
            else -> throw RuntimeException("Função tamanho só funciona com listas, mapas ou textos")
        }
    })
}



