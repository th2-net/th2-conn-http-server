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
 */

import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import testimpl.TestClientOptions
import java.util.concurrent.*

private val LOGGER = KotlinLogging.logger { }

class TestServerResponse {
    companion object {
        private val server = TestServerManager(false)

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

        val clients = mutableListOf<TcpRawHttpClient>()
        val futures = mutableListOf<Future<RawHttpResponse<Void>>>()
        try {
            for (i in 0 until maxInstances) {
                futures.add(executor.submit(
                    Callable {
                        TcpRawHttpClient(TestClientOptions(false)).apply { clients.add(this) }.send(request)
                    }
                ))
                LOGGER.debug { "Futures created: ${i + 1}" }
            }

            repeat(maxInstances) {
                server.handleResponse()
                LOGGER.debug { "Server handled response number: ${it+1}" }
            }

            for (i in 0 until maxInstances) {
                futures[i].runCatching {
                    val response = get(15, TimeUnit.SECONDS).apply { LOGGER.debug { "[${i+1}] Feature returned response" } }
                    assertEquals(response.statusCode, 200)
                }.onFailure {
                    fail { "Can't get response ${i + 1}: \n$it" }
                }.onSuccess {
                    LOGGER.debug { "${i + 1} test passed" }
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