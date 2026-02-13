package core.gateways

import STATIC_PATH
import helpers.inferFileNameFromUrl
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes

class GithubGateway {
    private val log: Logger = LoggerFactory.getLogger("GithubGateway")
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private constructor()

    companion object {
        val instance: GithubGateway by lazy {
            GithubGateway()
        }
    }

    fun getLibrary(libUrl: String, destDir: Path, fileName: String? = null): Path {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(libUrl))
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofByteArray()
        )
        if (response.statusCode() !in 200..299)
            log.error("Erro ao obter arquivo da biblioteca: HTTP ${response.statusCode()} ($libUrl)")

        val finalName = fileName?.takeIf { it.isNotBlank() } ?: inferFileNameFromUrl(libUrl)
        val resolvedDestDir = if (destDir.isAbsolute) destDir else STATIC_PATH.resolve(destDir)
            .normalize()
        Files.createDirectories(resolvedDestDir)
        val target = resolvedDestDir.resolve(finalName)
        target.writeBytes(response.body())
        return target
    }
}
