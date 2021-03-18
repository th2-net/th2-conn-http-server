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

import com.exactpro.th2.httpserver.api.impl.BasicResponseManager
import com.exactpro.th2.httpserver.server.Th2HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import testimpl.TestClientOptions
import testimpl.TestResponseManager
import testimpl.TestServerOptions

class TestCode {
    companion object {
        private val th2server = Th2HttpServer(TestServerOptions())

        @BeforeAll @JvmStatic fun setUp() {
            val response = RawHttp().parseResponse("HTTP/1.1 200 OK\n" +
                    "Content-Type: text/plain\n" +
                    "Content-Length: 9\n" +
                    "\n" +
                    "something")
            this.th2server.start(TestResponseManager(response))
        }

        @AfterAll @JvmStatic fun finish() {
            this.th2server.stop()
        }

    }

    @Test fun test() {
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

        assertEquals(response?.statusCode, 200)
        client.close()
    }


}