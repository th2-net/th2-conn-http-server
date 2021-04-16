import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import rawhttp.core.RawHttp
import rawhttp.core.client.TcpRawHttpClient
import testimpl.TestClientOptions
import testimpl.TestServerManager

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
        val request = RawHttp().parseRequest(
            """
            GET / HTTP/1.1
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
        )

        val executor: ExecutorService = Executors.newCachedThreadPool()

        val maxInstances = GlobalVariables.THREADS

        val clients = List(maxInstances) { TcpRawHttpClient(TestClientOptions(true)) }

        try {
            val futures = clients.map { executor.submit(Callable { it.send(request) }) }

            repeat(maxInstances) {
                server.handleResponse()
                LOGGER.debug { "Server handled response number: ${it+1}" }
            }

            futures.forEachIndexed { index, future ->
                future.runCatching {
                    val response = get(15, TimeUnit.SECONDS)
                    LOGGER.debug { "[${index + 1}] Feature returned response: $response" }
                    Assertions.assertEquals(response.statusCode, 200)
                    LOGGER.debug { "${index + 1} test passed" }
                }.onFailure {
                    fail("Can't get response ${index + 1}", it)
                }
            }
        } catch (e: Exception) {
            LOGGER.error(e) { "Can't handle stress test " }
            fail("Can't handle stress test with max: $maxInstances", e)
        } finally {
            clients.forEach { it.close() }
        }
    }
}