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

import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.httpserver.api.IResponseManager.*
import com.exactpro.th2.httpserver.api.IResponseManager
import com.exactpro.th2.httpserver.server.responses.HttpResponses
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.exactpro.th2.httpserver.util.*
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.lang.IllegalArgumentException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.HashMap
import java.util.UUID

class BasicResponseManager : IResponseManager {

    private val logger = KotlinLogging.logger {}

    private val dialogs = HashMap<String,(RawHttpResponse<*>) -> Unit>()

    private val generateSequence = Instant.now().run {
        AtomicLong(epochSecond * TimeUnit.SECONDS.toNanos(1) + nano)
    }::incrementAndGet

    private lateinit var context: ResponseManagerContext

    override fun init(value: ResponseManagerContext) {
        check(!::context.isInitialized) { "Response manager is already initialized" }
        context = value
    }

    override fun handleRequest(request: RawHttpRequest, answer: (RawHttpResponse<*>) -> Unit) {
        val uuid = UUID.randomUUID().toString()
        val sequence = generateSequence()
        dialogs[uuid] = answer
        logger.debug("Stored dialog: $uuid")
        val messageGroup = request.toBatch(context.connectionID, sequence, uuid)
        context.messageRouter.sendAll(messageGroup, QueueAttribute.SECOND.toString())
        logger.debug("Send on alias: ${context.connectionID.sessionAlias}")
    }

    override fun handleResponse(messages: MessageGroup) {
        val response = Th2Response.Builder().setGroup(messages).build()
        if (response.uuid == null) {
            throw IllegalArgumentException("UUID is required")
        }
        dialogs[response.uuid]?.let {
            it(response)
        } ?: run {
            throw IllegalArgumentException("UUID is not present")
        }
    }

    override fun close() {

    }

    /*
    private val DATE_HEADER_PROVIDER = DateHeaderProvider(Duration.ofSeconds(1))

    private val SERVER_HEADER = RawHttpHeaders.newBuilder()
        .with("Server", "Th2 http server")
        .build()
    fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<*>): RawHttpResponse<*> {
        return response.withHeaders(DATE_HEADER_PROVIDER.getHeader().and(SERVER_HEADER))
    }
    */
}