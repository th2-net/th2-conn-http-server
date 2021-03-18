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
import rawhttp.core.*
import rawhttp.core.body.BodyReader
import rawhttp.core.body.BytesBody
import rawhttp.core.body.EagerBodyReader
import java.nio.charset.StandardCharsets

private const val RESPONSE_MESSAGE = "Response"

private const val HEADERS_FIELD = "headers"
private const val HEADER_NAME_FIELD = "name"
private const val HEADER_VALUE_FIELD = "value"
private const val HEADERS_CODE_FIELD = "code"
private const val HEADERS_REASON_FIELD = "reason"
private const val CONTENT_TYPE_HEADER = "Content-Type"

private const val HEADER_VALUE_SEPARATOR = ";"

private const val CONTENT_TYPE_PROPERTY = "contentType"
private const val CODE_PROPERTY = HEADERS_CODE_FIELD
private const val REASON_PROPERTY = HEADERS_REASON_FIELD

private const val DEFAULT_CODE = 200
private const val DEFAULT_REASON = "OK"

class Th2Response private constructor(statusLine: StatusLine, headers: RawHttpHeaders, bodyReader: BodyReader, val uuid: String) :
    RawHttpResponse<MessageGroup>(null, null, statusLine, headers, bodyReader) {

    class Builder {
        private val metadata = hashMapOf<String,String>()

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
            metadata.putAll(body.metadata.propertiesMap)

            val code: Int = head.getInt(HEADERS_CODE_FIELD) ?: metadata[CODE_PROPERTY]?.toInt() ?: DEFAULT_CODE
            val reason = head.getString(HEADERS_REASON_FIELD) ?: metadata[REASON_PROPERTY] ?: DEFAULT_REASON
            val statusLine = StatusLine(HttpVersion.HTTP_1_1, code, reason)
            val httpBody = body.body.toByteArray().takeIf(ByteArray::isNotEmpty)

            val httpHeaders = RawHttpHeaders.newBuilder()
            head.getList(HEADERS_FIELD)?.forEach {
                require(it.hasMessageValue()) { "Item of '$HEADERS_FIELD' field list is not a message: ${it.toPrettyString()}" }
                val message = it.messageValue
                val name = message.getString(HEADER_NAME_FIELD) ?: error("Header message has no $HEADER_NAME_FIELD field: ${message.toPrettyString()}")
                val value = message.getString(HEADER_VALUE_FIELD) ?: error("Header message has no $HEADER_VALUE_FIELD field: ${message.toPrettyString()}")
                httpHeaders.overwrite(name, value)
            }
            if (httpBody != null && CONTENT_TYPE_HEADER !in httpHeaders.headerNames) {
                metadata[CONTENT_TYPE_PROPERTY]?.run {
                    split(HEADER_VALUE_SEPARATOR).forEach {
                        httpHeaders.with(CONTENT_TYPE_HEADER, it.trim())
                    }
                }
            }
            val uuid = head.metadata.propertiesMap["uuid"] ?: body.metadata.propertiesMap["uuid"]
            checkNotNull(uuid) { "UUID is required" }
            return Th2Response(statusLine, httpHeaders.build(), EagerBodyReader(httpBody), uuid)
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

