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

import com.exactpro.th2.httpserver.api.IResponseManager
import com.exactpro.th2.httpserver.server.headers.DateHeaderProvider
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.time.Duration

class BasicResponseManager : IResponseManager {

    private val DATE_HEADER_PROVIDER = DateHeaderProvider(Duration.ofSeconds(1))

    private val SERVER_HEADER = RawHttpHeaders.newBuilder()
        .with("Server", "Th2 http server")
        .build()

    override fun init() {

    }

    override fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<Void>): RawHttpResponse<Void> {
        return response.withHeaders(DATE_HEADER_PROVIDER.getHeader().and(SERVER_HEADER))
    }

    override fun close() {

    }

}