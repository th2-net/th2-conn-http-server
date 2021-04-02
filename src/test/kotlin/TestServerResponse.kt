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

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.message
import com.exactpro.th2.httpserver.server.Th2HttpServer
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.google.protobuf.ByteString
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import rawhttp.core.RawHttp
import rawhttp.core.client.TcpRawHttpClient
import testimpl.TestClientOptions
import testimpl.TestServerOptions
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val LOGGER = KotlinLogging.logger { }

class TestServerResponse {
    companion object {
        val response = { uuid: String ->
            val responseMessage = message("Response", Direction.FIRST, "somealias").apply {
                addField("code", 200)
                addField("reason", "Test reason")
                metadataBuilder.protocol = "http"
                metadataBuilder.putProperties("uuid", uuid)
            }.build().apply { LOGGER.debug { "Header message is created" } }

            val bodyMessage = RawMessage.newBuilder().apply {
                body = ByteString.copyFrom("SOME BYTES".toByteArray())
                metadata = metadataBuilder.putProperties("contentType", "application").build()
            }.build().apply { LOGGER.debug { "Body message is created" } }

            Th2Response.Builder().setHead(responseMessage).setBody(bodyMessage).build()
        }

        private val options = TestServerOptions(false)
        private val th2server = Th2HttpServer({ _: String, _: String, _: Throwable? -> Event.start()}, options)

        @BeforeAll
        @JvmStatic
        fun setUp() {
            this.th2server.start()
        }

        @AfterAll
        @JvmStatic
        fun finish() {
            this.th2server.stop()
        }
    }

    @Test
    fun stressTest() {
        val client = TcpRawHttpClient(TestClientOptions(false))
        val request = RawHttp().parseRequest(
            """
            GET / HTTP/1.1
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
        )

        val executor: ExecutorService = Executors.newCachedThreadPool()


        val maxTimes = 30

        try {
            for (i in 0 until maxTimes) {

                val future = executor.submit(
                    Callable {
                        client.send(request)
                    }
                )

                val th2response = response(options.queue.take())

                th2server.handleResponse(th2response)

                future.runCatching {
                    val response = get(10, TimeUnit.SECONDS).apply { LOGGER.debug { "Feature returned response" } }
                    assertEquals(response.statusCode, 200)
                }.onFailure {
                    fail { "Can't get response ${i + 1}: \n$it" }
                }.onSuccess {
                    LOGGER.debug { "${i + 1} test passed" }
                }
            }
        } catch (e: Exception) {
            LOGGER.error(e) { "Can't handle stress test " }
            fail { "Can't handle stress test with max: $maxTimes" }
        } finally {
            client.close()
        }
    }

}