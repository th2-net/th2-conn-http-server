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


import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.httpserver.api.IResponseManager.*
import com.exactpro.th2.httpserver.api.IResponseManager
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
    private val dialogs = HashMap<String,RequestData>()
    data class RequestData(val request: RawHttpRequest, val answer: (RawHttpResponse<*>) -> Unit)

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
        dialogs[uuid] = RequestData(request, answer)
        logger.debug("Stored dialog: $uuid")
        publishMessage(request, uuid)
        logger.debug("Send on alias: ${context.connectionID.sessionAlias}")
    }

    override fun handleResponse(messages: MessageGroup) {
        val response = Th2Response.Builder().setGroup(messages).build()
        val data = dialogs[response.uuid] ?: throw IllegalArgumentException("UUID is not present in store")
        data.answer(response)
        publishMessage(data.request, response)
    }

    override fun close() {

    }

    private fun publishMessage(request: RawHttpRequest, uuid: String) {
        context.messageRouter.sendAll(
            request.toBatch(context.connectionID, generateSequence(), uuid),
            QueueAttribute.SECOND.toString())
    }

    private fun publishMessage(request: RawHttpRequest, response: Th2Response) {
        context.messageRouter.sendAll(
            response.toBatch(context.connectionID, generateSequence(), request),
            QueueAttribute.FIRST.toString())
    }

}