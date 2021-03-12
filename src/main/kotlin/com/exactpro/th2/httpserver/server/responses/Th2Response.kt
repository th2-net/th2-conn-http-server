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

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.getInt
import com.exactpro.th2.common.message.getList
import com.exactpro.th2.common.message.getString
import com.exactpro.th2.httpserver.util.toPrettyString
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import rawhttp.core.*

const val RESPONSE_MESSAGE = "Response"

const val HEADERS_FIELD = "headers"
const val HEADER_NAME_FIELD = "name"
const val HEADER_VALUE_FIELD = "value"
const val HEADERS_CODE_FIELD = "code"
const val HEADERS_REASON_FIELD = "reason"

const val CODE_PROPERTY = HEADERS_CODE_FIELD
const val REASON_PROPERTY = HEADERS_REASON_FIELD

const val DEFAULT_CODE = 200
const val DEFAULT_REASON = "OK"

class Th2Response(statusLine: StatusLine?, headers: RawHttpHeaders?, val uuid: String?) :
    RawHttpResponse<MessageGroup>(null, null, statusLine, headers, null) {

    class Builder {
        private val additionalMetadata = hashMapOf<String,String>()

        private var head: Message = Message.getDefaultInstance()
        private var body: RawMessage = RawMessage.getDefaultInstance()

        fun setHead(message: Message) : Builder {
            this.head = message
            return this
        }

        fun setBody(message: RawMessage) : Builder {
            this.body = message
            return this
        }

        fun with(key: String, value: String ) : Builder{
            additionalMetadata[key] = value
            return this
        }

        fun setGroup(messages: MessageGroup) : Builder {
            when (messages.messagesCount) {
                0 -> error("Message group is empty")
                1 -> messages.getMessages(0).run {
                    when {
                        hasMessage() -> head = message.requireType(RESPONSE_MESSAGE)
                        hasRawMessage() -> body = rawMessage
                        else -> error("Single message in group is neither parsed nor raw: ${toPrettyString()}")
                    }
                }
                2 -> {
                    head = messages.getMessages(0).toParsed("Head").requireType(RESPONSE_MESSAGE)
                    body = messages.getMessages(1).toRaw("Body")
                }
                else -> error("Message group contains more than 2 messages")
            }
            return this
        }

        fun build(): Th2Response {
            val metadata = body.metadata.propertiesMap
            metadata.putAll(additionalMetadata)

            val code: Int = head.getInt(HEADERS_CODE_FIELD) ?: metadata[CODE_PROPERTY]?.toInt() ?: DEFAULT_CODE
            val reason = head.getString(HEADERS_REASON_FIELD) ?: metadata[REASON_PROPERTY] ?: DEFAULT_REASON
            val statusLine = StatusLine(HttpVersion.HTTP_1_1, code, reason)

            val httpHeaders = RawHttpHeaders.newBuilder()
            head.getList(HEADERS_FIELD)?.forEach {
                require(it.hasMessageValue()) { "Item of '$HEADERS_FIELD' field list is not a message: ${it.toPrettyString()}" }
                val message = it.messageValue
                val name = message.getString(HEADER_NAME_FIELD) ?: error("Header message has no $HEADER_NAME_FIELD field: ${message.toPrettyString()}")
                val value = message.getString(HEADER_VALUE_FIELD) ?: error("Header message has no $HEADER_VALUE_FIELD field: ${message.toPrettyString()}")
                httpHeaders.with(name, value)
            }
            return Th2Response(statusLine, httpHeaders.build(), head.metadata.propertiesMap["uuid"])
        }

    }
}

private fun Message.requireType(type: String): Message = apply {
    check(metadata.messageType == type) { "Invalid message type: $type" }
}

private fun AnyMessage.toParsed(name: String): Message = run {
    require(hasMessage()) { "$name is not a parsed message: ${toPrettyString()}" }
    message
}

private fun AnyMessage.toRaw(name: String): RawMessage = run {
    require(hasRawMessage()) { "$name is not a raw message: ${toPrettyString()}" }
    rawMessage
}

