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

package com.exactpro.th2.httpserver.server.options

import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.httpserver.util.toBatch
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class Th2ServerOptions(
    private val port: Int,
    private val handleResponse: (RawHttpRequest, RawHttpResponse<*>) -> Unit,
    private val handleRequest: (RawHttpRequest) -> Unit) : ServerOptions {

    private val logger = KotlinLogging.logger {}

    override fun getServerSocket(): ServerSocket {
        logger.info("Server socket on port:${port} created")
        return ServerSocket(port)
    }

    override fun onResponse(request: RawHttpRequest, response: RawHttpResponse<*>) {
        handleResponse(request, response)
    }

    override fun onRequest(request: RawHttpRequest) {
        handleRequest(request)
    }
}