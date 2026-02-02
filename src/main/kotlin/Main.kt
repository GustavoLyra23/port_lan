import core.Interpreter
import helpers.solvePath
import helpers.validateFile
import org.antlr.v4.runtime.*
import org.gustavolyra.PlarLexer
import org.gustavolyra.PlarParser
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

var interpreter = Interpreter()
val STATIC_PATH: Path = Path.of(System.getProperty("user.dir"))

fun main() {
    println("Iniciando Interpretador Plar")
    when {
//       args.getOrNull(0) == "run" && args.size > 1 -> execFile(args[1])
        //REPL direto...
        else -> interactiveMode()
    }
}

fun interactiveMode() {
    println("Digite 'exit' para sair")
    println("Digite 'run <caminho>' para executar um arquivo")
    while (true) {
        print("> ")
        val input = readlnOrNull()?.trim() ?: continue
        when {
            input == "exit" -> exitProcess(0)
            input.startsWith("run ") -> {
                val path = input.substring(4).trim()
                val file = solvePath(path)
                execFile(file)
            }

            input == "reset" -> interpreter = Interpreter()
            else -> execEngine(input)
        }
    }
}

fun execFile(file: Path) {
    try {
        if (!validateFile(file.toFile())) return
        val fileData = file.readText()
        execEngine(fileData)
    } catch (e: Exception) {
        println("Erro ao ler/executar o arquivo: ${e.message}")
    }
}


fun execEngine(code: String) {
    try {
        val input = CharStreams.fromString(code)
        val lexer = PlarLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = PlarParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String?,
                e: RecognitionException?
            ) {
                println("Erro de sintaxe na linha $line:$charPositionInLine")
            }
        })

        val tree = parser.programa()
        if (tree == null) {
            println("ERRO: Analise sintatica falhou arvore sintática nula...!")
            return
        }
        interpreter.interpret(tree)
    } catch (e: Exception) {
        println("Erro ao executar o programa ${e.message}")
    }
}


