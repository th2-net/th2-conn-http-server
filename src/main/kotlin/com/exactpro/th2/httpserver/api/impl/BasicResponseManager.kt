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

import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.httpserver.api.IResponseManager.*
import com.exactpro.th2.httpserver.api.IResponseManager
import com.exactpro.th2.httpserver.server.responses.HttpResponses
import com.exactpro.th2.httpserver.util.*
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
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
        logger.debug("Stored dialog: $sequence")
        context.messageRouter.send(request.toBatch(context.connectionID, sequence, uuid), QueueAttribute.FIRST.toString())
    }

    override fun handleResponse(messages: MessageGroup) {
        val response = messages.toResponse()
        val uuid = messages.run {
            when (messagesCount) {
                0 -> error("Message group is empty")
                1 -> getMessages(0).run {
                    when {
                        hasMessage() -> message.metadata.propertiesMap["uuid"]
                        hasRawMessage() -> rawMessage.metadata.propertiesMap["uuid"]
                        else -> error("Single message in group is neither parsed nor raw: ${toPrettyString()}")
                    }
                }
                2 -> {
                    getMessages(0).message.metadata.propertiesMap["uuid"]
                }
                else -> error("Message group contains more than 2 messages")
            }
        }
        //TODO: GET ANSWER BY SEQUENCE
        logger.debug("Handling response from mq: $messages")
        dialogs[uuid]?.let { it(HttpResponses.SERVER_ERROR_500_HTTP1_1) }
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