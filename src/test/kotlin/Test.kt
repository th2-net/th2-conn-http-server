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

import com.exactpro.th2.httpserver.server.Th2HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import rawhttp.core.server.TcpRawHttpServer
import testimpl.TestClientOptions
import testimpl.TestRouter
import testimpl.TestServerOptions
import java.io.IOException
import java.util.*

class Test {
    companion object {
        private val th2server = Th2HttpServer(TestServerOptions())
        private val standardServer = TcpRawHttpServer(GlobalVariables.port+1)

        val request =  {port: Int -> RawHttp().parseRequest(
            """
            GET / HTTP/1.1
            Host: localhost:$port
            User-Agent: client RawHTTP
            """.trimIndent()
        )}

        @BeforeAll @JvmStatic fun setUp() {
            this.th2server.start(TestRouter())
            this.standardServer.start {
                val body = "Hello RawHTTP!"
                val response: RawHttpResponse<*> = RawHttp().parseResponse(
                  """
                  HTTP/1.1 200 OK
                  Content-Type: plain/text
                  Content-Length: ${body.length}
                  
                  $body
                  """.trimIndent()
                )
                Optional.of(response)
            }
        }

        @AfterAll @JvmStatic fun finish() {
            this.th2server.stop()
            this.standardServer.stop()
        }

    }

    @Test fun testAgainstDefault() {
        val client = TcpRawHttpClient(TestClientOptions())


        val th2request = request(GlobalVariables.port)

        val standardRequest = request(GlobalVariables.port+1)

        for (i in 0..4) {
            val th2response = try {
                client.send(th2request).eagerly()
            } catch (e: IOException) {
                null
            }
            val standardResponse = try {
                client.send(standardRequest).eagerly()
            } catch (e: IOException) {
                null
            }

            assertEquals(th2response?.statusCode, standardResponse?.statusCode)
            Thread.sleep(150L)
        }
        client.close()
    }

    @Test fun testTh2CodeHttp() {
        val client = TcpRawHttpClient(TestClientOptions())
        val th2request = request(GlobalVariables.port)

        for (i in 0..4) {
            var response: RawHttpResponse<*>?


            client.run {
                response = send(th2request).eagerly()

                Thread.sleep(150L)
            }

            assertEquals(response?.statusCode, 200)
        }
        client.close()
    }

}