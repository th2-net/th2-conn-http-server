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

package testimpl

import com.exactpro.th2.httpserver.api.IRouter
import mu.KotlinLogging
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse

class TestRouter : IRouter {
    private val logger = KotlinLogging.logger {}
    override fun route(request: RawHttpRequest, answer: (RawHttpResponse<*>) -> Unit) {
        logger.info("Received request: \n$request")
        val body = "Hello RawHTTP!"
        val response = RawHttp().parseResponse(
            """
              HTTP/1.1 200 OK
              Content-Type: plain/text
              Content-Length: ${body.length}
              
              $body
              """.trimIndent()
        )
        logger.info("Send response: \n$response")
        answer(response)
    }

    override fun init() {
        TODO("Not yet implemented")
    }

    override fun close() {

    }

}