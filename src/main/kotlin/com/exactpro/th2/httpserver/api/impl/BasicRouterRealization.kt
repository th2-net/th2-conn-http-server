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

package com.exactpro.th2.httpserver.api.impl

import com.exactpro.th2.httpserver.api.IRouter
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse

class BasicRouterRealization : IRouter {
    override fun route(request: RawHttpRequest, answer: (RawHttpResponse<*>) -> Unit) {
        val http = RawHttp()
        val body = "Hello RawHTTP!"

        val response = http.parseResponse(
            """
              HTTP/1.1 200 OK
              Content-Type: plain/text
              Content-Length: ${body.length}
              
              $body
              """.trimIndent()
        ).let {
            if (request.method == "HEAD" && it.body.isPresent) it.withBody(null, false)
            else if(!it.body.isPresent && RawHttp.responseHasBody(it.startLine, request.startLine)) it.withHeaders(
                RawHttpHeaders.CONTENT_LENGTH_ZERO, true)
            else it
        }

        answer(response)
    }

    override fun init() {

    }

    override fun close() {

    }

}