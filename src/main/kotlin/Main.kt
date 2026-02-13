import core.Interpreter
import core.gateways.GithubGateway
import core.io.AnimateCLI
import helpers.solvePath
import helpers.validateFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.antlr.v4.runtime.*
import org.gustavolyra.MagLexer
import org.gustavolyra.MagParser
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

var interpreter = Interpreter()
val STATIC_PATH: Path = Path.of(System.getProperty("user.dir")) //cwd
private val log: Logger = LoggerFactory.getLogger("Main")

fun main() = runBlocking() {
    log.info("Iniciando Interpretador Mag")
    interactiveMode(this);
}

suspend fun interactiveMode(scope: CoroutineScope) {
    log.info("Digite 'sair' para interromper o programa")
    log.info("Digite 'run <caminho>' para executar um arquivo")
    while (true) {
        print("→ ")
        val input = readlnOrNull()?.trim() ?: continue
        when {
            input == "sair" -> exitProcess(0)
            input.startsWith("importar") -> {
                val animJob = scope.launch {
                    AnimateCLI.runLoadAnimation()
                }
                // TODO: find a better way to validate this input
                val inputArray = input.split(" ");
                GithubGateway.instance.getLibrary(
                    inputArray[1],
                    Path.of("bibliotecas"), ""
                )
                animJob.cancel()
                println()
            }

            input.startsWith("run ") -> {
                val path = input.substring(4).trim()
                val file = solvePath(path)
                execFile(file)
            }

            input == "reset" -> interpreter = Interpreter()
            else -> execInterpreter(input)
        }
    }
}

fun execFile(file: Path) {
    try {
        if (!validateFile(file.toFile())) return
        val fileData = file.readText()
        execInterpreter(fileData)
    } catch (e: Exception) {
        log.error("Erro ao ler/executar o arquivo: ${e.message}")
    }
}


fun execInterpreter(code: String) {
    try {
        val input = CharStreams.fromString(code)
        val lexer = MagLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MagParser(tokens)
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
                log.error("Erro de sintaxe na linha $line:$charPositionInLine")
            }
        })

        val tree = parser.programa()
        if (tree == null) {
            log.error("Analise sintatica falhou arvore sintatica nula!")
            return
        }
        interpreter.interpret(tree)
    } catch (e: Exception) {
        log.error("Erro ao executar o programa ${e.message}")
    }
}


