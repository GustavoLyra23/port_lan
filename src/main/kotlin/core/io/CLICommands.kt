package core.io

import mainLog
import java.io.IOException
import java.nio.file.Path

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

fun runCLICommand(
    command: List<String>,
    workingDir: Path? = null,
): CommandResult {
    val pb = ProcessBuilder(command)
    if (workingDir != null)
        pb.directory(workingDir.toFile())
    pb.redirectErrorStream(true)
    try {
        pb.start()
    } catch (e: IOException) {
        mainLog.error(e.message)
    }
    return CommandResult(
        0,
        "",
        ""
    )
}


