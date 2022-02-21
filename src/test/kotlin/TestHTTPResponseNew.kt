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

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import rawhttp.core.RawHttp
import testimpl.TestServerManager

/*
 * HTTP 1.1
 */
class TestHTTPResponseNew {
    companion object {
        private val server = TestServerManager(false) {
            fail("Server must be without errors", it)
        }

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
    fun responseParallelTest() {
        val request = RawHttp().parseRequest(
            """
            GET / HTTP/1.1
            Connection: close
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
        )

        server.stressSpam(request)
    }

    //@Test
    fun responseLineTest() {
        val requests = listOf(
            RawHttp().parseRequest(
                """
            GET / HTTP/1.1
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
            ),
            RawHttp().parseRequest(
                """
            GET / HTTP/1.1
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
            ),
            RawHttp().parseRequest(
                """
            GET / HTTP/1.1
            Connection: close
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
            ),
            RawHttp().parseRequest(
                """
            GET / HTTP/1.1
            Host: localhost:${GlobalVariables.PORT}
            User-Agent: client RawHTTP
            """.trimIndent()
            )

        )
        //TODO("NEED TO IMPLEMENT PERSIST CONNECTION TEST")
        //server.stressSpam(request)
    }

}