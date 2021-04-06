import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import rawhttp.core.RawHttp
import rawhttp.core.client.TcpRawHttpClient
import testimpl.TestClientOptions
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val LOGGER = KotlinLogging.logger { }

class TestHTTPSResponse {
    companion object {
        private val server = TestServerManager(true)

        @BeforeAll
        @JvmStatic
        fun setUp() {
            server.start()
        }

        @AfterAll
        @JvmStatic
        fun finish() {
            server.close()
        }
    }

    @Test
    fun stressTest() {
        val client = TcpRawHttpClient(TestClientOptions(true))
        val request = RawHttp().parseRequest(
            """
            GET / HTTP/1.1
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
        )

        val executor: ExecutorService = Executors.newCachedThreadPool()


        val maxTimes = 10

        try {
            for (i in 0 until maxTimes) {

                val future = executor.submit(
                    Callable {
                        client.send(request)
                    }
                )
                server.handleResponse()

                future.runCatching {
                    val response = get(15, TimeUnit.SECONDS).apply { LOGGER.debug { "Feature returned response" } }
                    Assertions.assertEquals(response.statusCode, 200)
                    LOGGER.debug { "Response status code: ${response.statusCode}" }
                }.onFailure {
                    fail { "Can't get response ${i + 1}: \n$it" }
                }.onSuccess {
                    LOGGER.debug { "${i + 1} test passed" }
                }
            }
        } catch (e: Exception) {
            LOGGER.error(e) { "Can't handle stress test " }
            fail("Can't handle stress test with max: $maxTimes", e)
        } finally {
            client.close()
        }
    }
}