/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.exactpro.th2.http.server.response

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.message.getInt
import com.exactpro.th2.common.message.getList
import com.exactpro.th2.common.message.getString
import com.exactpro.th2.http.server.util.requireType
import com.exactpro.th2.http.server.util.toParsed
import com.exactpro.th2.http.server.util.toPrettyString
import rawhttp.core.body.EagerBodyReader
import com.exactpro.th2.http.server.util.toRaw
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpResponse
import rawhttp.core.StatusLine

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

data class CommonData(val uuid: String, val eventId: EventID, val messagesId: List<MessageID>) {
    class Builder {
        private val metadata = hashMapOf<String, String>()

        private var head: Message = Message.getDefaultInstance()
        private var body: RawMessage = RawMessage.getDefaultInstance()
        private var eventId: EventID = EventID.getDefaultInstance()

        fun setHead(message: Message) = apply {
            head = message.requireType(RESPONSE_MESSAGE)
            if (message.hasParentEventId()) {
                eventId = message.parentEventId
            }

        }

        fun setBody(message: RawMessage) = apply {
            body = message
            if (message.hasParentEventId()) {
                eventId = message.parentEventId
            }
        }

        fun setGroup(messages: MessageGroup) = apply {
            when (messages.messagesCount) {
                0 -> error("Message group is empty")
                1 -> messages.getMessages(0).run {
                    when {
                        hasMessage() -> setHead(message)
                        hasRawMessage() -> setBody(rawMessage)
                        else -> error("Single message in group is neither parsed nor raw: ${toPrettyString()}")
                    }
                }
                2 -> {
                    setHead(messages.getMessages(0).toParsed("Head"))
                    setBody(messages.getMessages(1).toRaw("Body"))
                }
                else -> error("Message group contains more than 2 messages")
            }
        }

        fun build(): RawHttpResponse<CommonData> {
            metadata.putAll(body.metadata.propertiesMap)

            val code: Int = head.getInt(HEADERS_CODE_FIELD) ?: metadata[CODE_PROPERTY]?.toInt() ?: DEFAULT_CODE
            val reason = head.getString(HEADERS_REASON_FIELD) ?: metadata[REASON_PROPERTY] ?: DEFAULT_REASON
            val statusLine = StatusLine(HttpVersion.HTTP_1_1, code, reason)
            val httpBody = body.body.toByteArray()

            val httpHeaders = RawHttpHeaders.newBuilder()
            httpHeaders.overwrite("Content-Length", httpBody.size.toString())
            head.getList(HEADERS_FIELD)?.forEach {
                require(it.hasMessageValue()) { "Item of '$HEADERS_FIELD' field list is not a message: ${it.toPrettyString()}" }
                val message = it.messageValue
                val name = message.getString(HEADER_NAME_FIELD)
                    ?: error("Header message has no $HEADER_NAME_FIELD field: ${message.toPrettyString()}")
                val value = message.getString(HEADER_VALUE_FIELD)
                    ?: error("Header message has no $HEADER_VALUE_FIELD field: ${message.toPrettyString()}")
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

            val messagesId = listOf(head.metadata.id, body.metadata.id)

            return RawHttpResponse<CommonData>(
                CommonData(uuid, eventId, messagesId),
                null,
                statusLine,
                httpHeaders.build(),
                EagerBodyReader(httpBody)
            )
        }

    }
}

