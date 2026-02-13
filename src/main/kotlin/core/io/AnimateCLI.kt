package core.io

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class AnimateCLI {
    companion object {
        private val frames = listOf(
            "|",
            "/",
            "-",
            "\\",
        )

        suspend fun runLoadAnimation(intervalMs: Long = 200, prefix: String = "") {
            val ctx = currentCoroutineContext()

            while (ctx.isActive) {
                for (frame in frames) {
                    if (!ctx.isActive) break

                    val text = "$prefix$frame"
                    print("\r$text")
                    print(" ".repeat(20))
                    print("\r$text")

                    delay(intervalMs)
                }
            }
            print("\r")
            print(" ".repeat(4))
            print("\r")
        }
    }
}
