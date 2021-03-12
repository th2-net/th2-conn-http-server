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

import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.message
import com.exactpro.th2.httpserver.server.Th2HttpServer
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.google.protobuf.ByteString
import mu.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import testimpl.TestClientOptions
import testimpl.TestResponseManager
import testimpl.TestServerOptions
import java.util.logging.Logger

private val LOGGER = KotlinLogging.logger { }

class TestTh2Response {
    companion object {
        private val th2server = Th2HttpServer(TestServerOptions())

        @BeforeAll
        @JvmStatic fun setUp() {
            val responseMessage = message("Response", Direction.FIRST, "somealias").apply {
                addField("TestFieldOne", "One")
                addField("TestFieldTwo", "Two")
                addField("TestFieldThree", "Three")
                metadataBuilder.protocol = "http"
            }.build()
            val bodyMessage = RawMessage.newBuilder().setBody(ByteString.copyFrom("SOME BYTES".toByteArray())).build()
            val response = Th2Response.Builder().setHead(responseMessage).setBody(bodyMessage).build()
            LOGGER.info("Created response $response")
            this.th2server.start(TestResponseManager(response))
        }

        @AfterAll
        @JvmStatic fun finish() {
            this.th2server.stop()
        }
    }

    @Test
    fun test() {
        val request =  {port: Int -> RawHttp().parseRequest(
            """
            GET / HTTP/1.1
            Host: localhost:$port
            User-Agent: client RawHTTP
            """.trimIndent()
        )}

        val client = TcpRawHttpClient(TestClientOptions())
        val th2request = request(GlobalVariables.port)

        val response: RawHttpResponse<*>? = client.send(th2request).eagerly()

        Assertions.assertEquals(response?.statusCode, 200)
        client.close()
    }
}