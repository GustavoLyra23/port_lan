package core.gateways

import core.io.runCLICommand
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.http.HttpClient
import java.nio.file.Path
import kotlin.io.path.exists

class GithubGateway {
    private val log: Logger = LoggerFactory.getLogger("GithubGateway")
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private constructor()

    //singleton
    companion object {
        val instance: GithubGateway by lazy {
            GithubGateway()
        }
    }

    fun getLibrary(libUrl: String, destDir: Path) {
        val finalFolderName = libUrl.substringAfterLast("/").removeSuffix(".git")
        val targetDir = destDir.resolve(finalFolderName)
        if (targetDir.exists()) {
            runCLICommand(
                listOf("git", "fetch", "--all", "--prune"),
                workingDir = targetDir
            )
        } else {
            runCLICommand(
                listOf(
                    "git",
                    "clone",
                    libUrl,
                    targetDir.toString()
                ), workingDir = destDir
            )
        }
    }
}
