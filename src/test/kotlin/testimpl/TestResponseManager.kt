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

import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.httpserver.api.IResponseManager
import com.exactpro.th2.httpserver.server.headers.DateHeaderProvider
import com.exactpro.th2.httpserver.server.responses.HttpResponses
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.HashMap

class TestResponseManager(private val response: RawHttpResponse<*>) : IResponseManager {

    override fun init(value: IResponseManager.ResponseManagerContext) {

    }

    override fun handleRequest(request: RawHttpRequest, answer: (RawHttpResponse<*>) -> Unit) {
        answer(response)
    }

    override fun handleResponse(request: MessageGroup) {

    }

    override fun close() {

    }
}