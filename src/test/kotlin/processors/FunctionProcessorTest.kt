package processors

import core.processors.isReturnInvalid
import models.Environment
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class FunctionProcessorTest {

    @Test
    fun `returnInvalidFunction should return false when input is valid`() {
        val input = "Texto"
        val res = isReturnInvalid(input, Environment())
        assertFalse(res)
    }

    @Test
    fun `returnInvalidFunction should return true when input is invalid`() {
        val invalidInput = "Invalid"
        val res = isReturnInvalid(invalidInput, Environment())
        assertTrue(res)
    }

    @Test
    fun `returnInvalidFunction should return false when class exists in the Environment`() {
        val environment = Environment()
        val className = "classeTeste"
        environment.defineClass(className, null)
        val res = isReturnInvalid(className, environment)
        assertFalse(res)
    }

    @Test
    fun `returnInvalidFunction should return true when class does not exist in the Environment`() {
        val className = "classeTeste"
        val res = isReturnInvalid(className, Environment())
        assertTrue(res)
    }
}
