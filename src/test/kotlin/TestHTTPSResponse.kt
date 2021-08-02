/*
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

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
import rawhttp.core.RawHttpRequest
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
    fun `old http`() {
        val request = RawHttp().parseRequest(
            """
            GET / HTTP/1.0
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
        )
        stressTest(request)
    }

    @Test
    fun `new http`() {
        val request = RawHttp().parseRequest(
            """
            GET / HTTP/1.1
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
        )
        stressTest(request)
    }

    private fun stressTest(request: RawHttpRequest) {

        val executor: ExecutorService = Executors.newCachedThreadPool()

        val maxInstances = GlobalVariables.THREADS

        val clients = List(maxInstances) { TcpRawHttpClient(TestClientOptions(true)) }

        try {
            val futures = clients.map { executor.submit(Callable { it.send(request) }) to it }

            repeat(maxInstances) {
                server.handleResponse()
                LOGGER.debug { "Server handled response number: ${it+1}" }
            }

            futures.forEachIndexed { index, future ->
                future.runCatching {
                    val response = this.first.get(15, TimeUnit.SECONDS)
                    LOGGER.debug { "[${index + 1}] Feature returned response: $response" }
                    Assertions.assertEquals(response.statusCode, 200)
                    LOGGER.debug { "${index + 1} test passed" }
                    this.second.close()
                }.onFailure {
                    fail("Can't get response ${index + 1}", it)
                }
            }
        } catch (e: Exception) {
            LOGGER.error(e) { "Can't handle stress test " }
            fail("Can't handle stress test with max: $maxInstances", e)
        } finally {
            clients.forEach { runCatching(it::close) }
        }
    }
}