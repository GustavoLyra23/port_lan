package helpers

import STATIC_PATH
import constants.EXTENSION
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
        println("Formato do arquivo invalido! Use arquivos .pplus")
        return false
    }
    return true
}
