package helpers

import STATIC_PATH
import constants.EXTENSION
import models.Value
import java.io.File
import java.nio.file.Path

fun solvePath(pathName: String): Path {
    val path = Path.of(pathName)

    return if (path.isAbsolute) {
        path.normalize()
    } else {
        STATIC_PATH.resolve(path).normalize()
    }
}

fun validateFile(arquivo: File): Boolean {
    if (!arquivo.exists()) {
        println("Erro: Arquivo nao encontrado!")
        return false
    }
    if (!arquivo.name.endsWith(EXTENSION)) {
        println("Formato do arquivo invalido! Use arquivos .mag")
        return false
    }
    return true
}

fun getHostAndPortFromArgs(args: List<Value>): Pair<String, Int> {
    var host = Value.Text("localhost")
    var port = Value.Integer(8080)
    if (!args.isEmpty() && args.size >= 2) {
        host = args[0] as Value.Text
        port = args[1] as Value.Integer
    }
    return Pair(host.value, port.value)
}
