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
import com.exactpro.th2.httpserver.api.IResponseManager
import com.exactpro.th2.httpserver.api.IResponseManager.ResponseManagerContext
import com.exactpro.th2.httpserver.server.responses.Th2Response
import mu.KotlinLogging
import rawhttp.core.RawHttpResponse

class BasicResponseManager : IResponseManager {

    private val logger = KotlinLogging.logger {}

    private lateinit var answer: (RawHttpResponse<Th2Response>) -> Unit

    override fun init(value: ResponseManagerContext) {
        check(!::answer.isInitialized) { "Response manager is already initialized" }
        answer = value.answer
    }

    override fun handleResponse(messages: MessageGroup) {
        logger.debug { "Handling message from mq (Response)" }
        answer(Th2Response.Builder().setGroup(messages).build())
    }

    override fun close() {

    }

}