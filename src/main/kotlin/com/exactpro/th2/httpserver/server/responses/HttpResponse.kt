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

package com.exactpro.th2.httpserver.server.responses

import rawhttp.core.EagerHttpResponse
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.StatusLine
import rawhttp.core.body.EagerBodyReader
import java.nio.charset.StandardCharsets

internal class HttpResponses {

    companion object {
        val basicHeaders = RawHttpHeaders.newBuilderSkippingValidation()
            .with("Content-Type", "application")
            .with("Cache-Control", "no-cache")
            .with("Pragma", "no-cache")
            .build()
        val notFoundResponseBody = "Resource was not found.".toByteArray(StandardCharsets.US_ASCII)
        val serverErrorResponseBody = "A Server Error has occurred.".toByteArray(StandardCharsets.US_ASCII)

        val STATUS_404_HTTP1_0 = StatusLine(HttpVersion.HTTP_1_0, 404, "Not Found")
        val STATUS_404_HTTP1_1 = StatusLine(HttpVersion.HTTP_1_1, 404, "Not Found")
        val STATUS_500_HTTP1_0 = StatusLine(HttpVersion.HTTP_1_0, 500, "Server Error")
        val STATUS_500_HTTP1_1 = StatusLine(HttpVersion.HTTP_1_1, 500, "Server Error")
        val NOT_FOUND_404_HTTP1_1 = EagerHttpResponse<Void>(null, null, STATUS_404_HTTP1_1,
            RawHttpHeaders.newBuilderSkippingValidation(basicHeaders)
                .overwrite("Content-Length", Integer.toString(notFoundResponseBody.size))
                .build(), EagerBodyReader(notFoundResponseBody))
        val NOT_FOUND_404_HTTP1_0 = NOT_FOUND_404_HTTP1_1.withStatusLine(STATUS_404_HTTP1_0)
        val SERVER_ERROR_500_HTTP1_1 = EagerHttpResponse<Void>(null, null, STATUS_500_HTTP1_1,
        RawHttpHeaders.newBuilderSkippingValidation(basicHeaders)
        .overwrite("Content-Length", Integer.toString(serverErrorResponseBody.size))
        .build(), EagerBodyReader(serverErrorResponseBody))
        val SERVER_ERROR_500_HTTP1_0 = SERVER_ERROR_500_HTTP1_1.withStatusLine(STATUS_500_HTTP1_0)
        val  _100_CONTINUE = EagerHttpResponse<Void>(null, null, StatusLine(HttpVersion.HTTP_1_1, 100, "Continue"), RawHttpHeaders.empty(), null)

        fun getNotFoundResponse(httpVersion: HttpVersion): EagerHttpResponse<Void> {
            return if (httpVersion.isOlderThan(HttpVersion.HTTP_1_1)) {
                NOT_FOUND_404_HTTP1_0
            } else {
                NOT_FOUND_404_HTTP1_1
            }
        }

        fun getServerErrorResponse(httpVersion: HttpVersion): EagerHttpResponse<Void> {
            return if (httpVersion.isOlderThan(HttpVersion.HTTP_1_1)) {
                SERVER_ERROR_500_HTTP1_0
            } else {
                SERVER_ERROR_500_HTTP1_1
            }
        }

        fun get100ContinueResponse(): EagerHttpResponse<Void> {
            return _100_CONTINUE
        }
    }

}
