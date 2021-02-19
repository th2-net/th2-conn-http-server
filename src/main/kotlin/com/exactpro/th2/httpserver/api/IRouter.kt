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

package com.exactpro.th2.httpserver.api

import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.RequestLine

interface IRouter : AutoCloseable {
    /**
     * Route an incoming HTTP request.
     *
     * @param request HTTP request
     * @return a HTTP response to send to the client.
     * If an empty null is returned, the server will use a default 404 response.
     * If an Exception happens, a default 500 response is returned.
     */
    fun route(request: RawHttpRequest, answer: (RawHttpResponse<*>) -> Unit)

    /**
     * Get the HTTP response for a request that includes the `Expect` header with a `100-continue` value.
     *
     * @param requestLine message request-line
     * @param headers     message headers
     * @return interim response (with 100 status code) or final response (any other status code).
     * If an empty null is returned, the server returns a 100-response without any headers.
     */
    fun continueResponse(requestLine: RequestLine?, headers: RawHttpHeaders?): RawHttpResponse<Void>? {
        return null
    }

    fun init()
}