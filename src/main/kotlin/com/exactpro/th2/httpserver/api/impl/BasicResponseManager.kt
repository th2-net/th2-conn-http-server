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


import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.message.MessageRouter
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
import java.util.concurrent.ConcurrentHashMap

class BasicResponseManager : IResponseManager {

    private val logger = KotlinLogging.logger {}

    private lateinit var answer : (Th2Response) -> Unit

    override fun init(value: ResponseManagerContext) {
        check(!::answer.isInitialized ) { "Response manager is already initialized" }
        answer = value.answer
    }



    override fun handleResponse(messages: MessageGroup) {
        logger.debug { "Handling message from mq (Response)" }
        answer(Th2Response.Builder().setGroup(messages).build())
    }

    override fun close() {

    }

}