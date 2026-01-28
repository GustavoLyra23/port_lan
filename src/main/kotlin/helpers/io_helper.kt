package helpers

import java.nio.file.Path
import java.nio.file.Paths

fun getAbsolutePath(pathName: String): Path {
    return Paths.get(pathName).toAbsolutePath().normalize()
}
