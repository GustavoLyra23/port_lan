package processors

import extractValueToPrint
import helpers.getHostAndPortFromArgs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import models.Environment
import models.Value
import models.errors.ArquivoException
import models.errors.InputException
import models.errors.PlarRuntimeException
import processors.FileIOProcessor.readFile
import processors.FileIOProcessor.writeFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.*

fun defineDefaultFunctions(global: Environment) {
    registerIOFunctions(global)
    registerThreadFunctions(global)
    registerExceptionsFunctions(global)
    registerCollectionsFunctions(global)
}

fun registerIOFunctions(global: Environment) {
    global.define("ler", Value.Fun("ler", null, "Texto", global) { args ->
        Scanner(System.`in`).nextLine().let { Value.Text(it) }
    })
    global.define("readFile", Value.Fun("readFile", null, "Texto", global) { args ->
        if (args.isEmpty()) throw RuntimeException("Funcao readFile requer um argumento (caminho do arquivo)")
        if (args.size > 1) throw RuntimeException("Funcaoo readFile aceita apenas um argumento")

        val argVal = args[0]
        if (argVal !is Value.Text) {
            throw RuntimeException("Argumento deve ser um texto (caminho do arquivo)")
        }

        try {
            Value.Text(readFile(argVal.value))
        } catch (e: Exception) {
            throw ArquivoException("Erro ao ler arquivo '${argVal.value}': ${e.message}")
        }
    })
    global.define("writeFile", Value.Fun("writeFile", null, null, global) { args ->
        require(args.size in 2..3) { "Função writeFile requer 2 ou 3 argumentos" }
        val (path, data) = args.take(2)
        val append = args.getOrNull(2)

        require(path is Value.Text && data is Value.Text) {
            "Os dois primeiros argumentos devem ser do tipo Texto"
        }
        when (append) {
            null -> writeFile(path.value, data.value)
            is Value.Logic -> writeFile(
                path.value, data.value, append.value
            )

            else -> throw RuntimeException("O terceiro argumento deve ser do tipo Logico")
        }
        Value.Null
    })
    global.define("escrever", Value.Fun("escrever", null, null, global) { args ->
        val values = args.map { extractValueToPrint(it) }
        println(values.joinToString(" "))
        Value.Null
    })
    global.define("imprimir", Value.Fun("imprimir", null, null, global) { args ->
        val values = args.map { extractValueToPrint(it) }
        println(values.joinToString(" "))
        Value.Null
    })
    //TODO: rever funcoes socket
    global.define("ler_socket", Value.Fun("ler_socket", null, "Texto", global) { args ->
        try {
            val (host, port) = getHostAndPortFromArgs(args)
            val socket = ServerSocket()
            socket.bind(java.net.InetSocketAddress(host, port))
            val input = socket.accept().getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val response = reader.readLine()
            socket.close()
            Value.Text(response)
        } catch (e: Exception) {
            throw PlarRuntimeException("Nao foi possivel configurar o socket: ${e.message}")
        }
    })
    global.define("escrever_socket", Value.Fun("escrever_socket", null, null, global) { args ->
        try {
            if (args.isEmpty() || args.size != 1 && args.size < 3)
                throw InputException("argumentos invalidos pra socket_write")
            val (host, port) = getHostAndPortFromArgs(args)
            val socket = ServerSocket()
            socket.bind(java.net.InetSocketAddress(host, port))
            val output = socket.accept().getOutputStream()
            val writer = PrintWriter(output, true)
            val buffer = (if (args.size == 1) args[0] else args[2]) as Value.Text;
            writer.println(buffer.value)
            socket.close()
            Value.Null
        } catch (e: Exception) {
            throw PlarRuntimeException("Nao foi possivel configurar o socket: ${e.message}")
        }
    })


}

fun registerThreadFunctions(global: Environment) {
    global.define("executar", Value.Fun("executar", null, null, global) { args ->
        if (args.isEmpty() || args[0] !is Value.Fun) throw RuntimeException("Argumento invalido para a funcao.")
        val execFun = args[0] as Value.Fun
        val realArgs = args.drop(1)
        //run sincrono...
        runBlocking {
            launch {
                try {
                    execFun.implementation!!.invoke(realArgs)
                } catch (e: Exception) {
                    println("Erro na execucao da thread: ${e.message}")
                }
            }.join()
        }
        Value.Null
    })
    global.define("dormir", Value.Fun("aguardar", null, null, global) { args ->
        if (args.isEmpty()) throw RuntimeException("Função aguardar requer um argumento (milissegundos)")
        val time = args[0]
        if (time !is Value.Integer) throw RuntimeException("Argumento deve ser um número inteiro (milissegundos)")
        runBlocking {
            delay(time.value.toLong())
        }
        Value.Null
    })
}

fun registerExceptionsFunctions(global: Environment) {
    global.define("jogarError", Value.Fun("jogarError", null, null, global) { args ->
        if (args.isEmpty()) {
            throw RuntimeException("Funcaoo jogarError requer um argumento (mensagem de erro)")
        }
        val msg = args[0]
        if (msg !is Value.Text) {
            throw RuntimeException("Argumento deve ser um texto (mensagem de erro)")
        }
        throw RuntimeException(msg.value)
    })
}

fun registerCollectionsFunctions(global: Environment) {
    global.define("tamanho", Value.Fun("tamanho", null, "Inteiro", global) { args ->
        if (args.isEmpty()) {
            throw RuntimeException("Funcao tamanho requer um argumento (lista, mapa ou texto)")
        }

        when (val arg = args[0]) {
            is Value.List -> Value.Integer(arg.elements.size)
            is Value.Map -> Value.Integer(arg.elements.size)
            is Value.Text -> Value.Integer(arg.value.length)
            else -> throw RuntimeException("Funcao tamanho só funciona com listas, mapas ou textos")
        }
    })
}



