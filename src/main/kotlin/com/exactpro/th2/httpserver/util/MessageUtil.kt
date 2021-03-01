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

@file:JvmName("MessageUtil")

package com.exactpro.th2.httpserver.util

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.Direction.FIRST
import com.exactpro.th2.common.grpc.Direction.SECOND
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.getInt
import com.exactpro.th2.common.message.getList
import com.exactpro.th2.common.message.getString
import com.exactpro.th2.common.message.toTimestamp
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite.Builder
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import rawhttp.core.*
import rawhttp.core.HttpVersion.HTTP_1_1
import rawhttp.core.body.BytesBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Instant

private const val REQUEST_MESSAGE = "Request"
private const val RESPONSE_MESSAGE = "Response"

private const val METHOD_FIELD = "method"
private const val URI_FIELD = "uri"
private const val HEADERS_FIELD = "headers"
private const val HEADER_NAME_FIELD = "name"
private const val HEADER_VALUE_FIELD = "value"
private const val HEADERS_CODE_FIELD = "code"
private const val HEADERS_REASON_FIELD = "reason"

private const val METHOD_PROPERTY = METHOD_FIELD
private const val URI_PROPERTY = URI_FIELD
private const val CODE_PROPERTY = HEADERS_CODE_FIELD
private const val REASON_PROPERTY = HEADERS_REASON_FIELD

private const val DEFAULT_METHOD = "GET"
private const val DEFAULT_URI = "/"
private const val DEFAULT_CODE = 200
private const val DEFAULT_REASON = "OK"


private fun createRequest(head: Message, body: RawMessage): RawHttpRequest {
    val metadata = body.metadata.propertiesMap
    val method = head.getString(METHOD_FIELD) ?: metadata[METHOD_PROPERTY] ?: DEFAULT_METHOD
    val uri = head.getString(URI_FIELD) ?: metadata[URI_PROPERTY] ?: DEFAULT_URI

    val httpRequestLine = RequestLine(method, URI(uri), HTTP_1_1)
    val httpHeaders = RawHttpHeaders.newBuilder()
    val httpBody = BytesBody(body.body.toByteArray())

    head.getList(HEADERS_FIELD)?.forEach {
        require(it.hasMessageValue()) { "Item of '$HEADERS_FIELD' field list is not a message: ${it.toPrettyString()}" }
        val message = it.messageValue
        val name = message.getString(HEADER_NAME_FIELD) ?: error("Header message has no $HEADER_NAME_FIELD field: ${message.toPrettyString()}")
        val value = message.getString(HEADER_NAME_FIELD) ?: error("Header message has no $HEADER_VALUE_FIELD field: ${message.toPrettyString()}")
        httpHeaders.with(name, value)
    }

    return RawHttpRequest(httpRequestLine, httpHeaders.build(), null, null).withBody(httpBody)
}


private fun createResponse(head: Message, body: RawMessage): RawHttpResponse<*> {
    val metadata = body.metadata.propertiesMap
    val code: Int = head.getInt(HEADERS_CODE_FIELD) ?: metadata[CODE_PROPERTY]?.toInt() ?: DEFAULT_CODE
    val reason = head.getString(HEADERS_REASON_FIELD) ?: metadata[REASON_PROPERTY] ?: DEFAULT_REASON
    val statusLine = StatusLine(HTTP_1_1, code, reason)

    val httpHeaders = RawHttpHeaders.newBuilder()
    head.getList(HEADERS_FIELD)?.forEach {
        require(it.hasMessageValue()) { "Item of '$HEADERS_FIELD' field list is not a message: ${it.toPrettyString()}" }
        val message = it.messageValue
        val name = message.getString(HEADER_NAME_FIELD) ?: error("Header message has no $HEADER_NAME_FIELD field: ${message.toPrettyString()}")
        val value = message.getString(HEADER_NAME_FIELD) ?: error("Header message has no $HEADER_VALUE_FIELD field: ${message.toPrettyString()}")
        httpHeaders.with(name, value)
    }

    //body.metadata.id.sequence

    return RawHttpResponse(null, null, statusLine, httpHeaders.build(), null)
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

fun MessageGroup.toResponse(): RawHttpResponse<*> = when (messagesCount) {
    0 -> error("Message group is empty")
    1 -> getMessages(0).run {
        when {
            hasMessage() -> createResponse(message.requireType(RESPONSE_MESSAGE), RawMessage.getDefaultInstance())
            hasRawMessage() -> createResponse(Message.getDefaultInstance(), rawMessage)
            else -> error("Single message in group is neither parsed nor raw: ${toPrettyString()}")
        }
    }
    2 -> {
        val head = getMessages(0).toParsed("Head").requireType(RESPONSE_MESSAGE)
        val body = getMessages(1).toRaw("Body")
        createResponse(head, body)
    }
    else -> error("Message group contains more than 2 messages")
}


private inline operator fun <T : Builder> T.invoke(block: T.() -> Unit) = apply(block)

fun MessageOrBuilder.toPrettyString(): String = JsonFormat.printer().omittingInsignificantWhitespace().includingDefaultValueFields().print(this)

private fun RawMessage.Builder.toBatch() = run(AnyMessage.newBuilder()::setRawMessage)
    .run(MessageGroup.newBuilder()::addMessages)
    .run(MessageGroupBatch.newBuilder()::addGroups)
    .build()

private fun ByteArrayOutputStream.toBatch(
    connectionId: ConnectionID,
    direction: Direction,
    sequence: Long,
    metadataProperties: Map<String, String>
) = RawMessage.newBuilder().apply {
    this.body = ByteString.copyFrom(toByteArray())
    this.metadataBuilder {
        putAllProperties(metadataProperties)
        this.timestamp = Instant.now().toTimestamp()
        this.idBuilder {
            this.connectionId = connectionId
            this.direction = direction
            this.sequence = sequence
        }
    }
}.toBatch()

private fun HttpMessage.toBatch(connectionId: ConnectionID, direction: Direction, sequence: Long, request: RawHttpRequest, id: String): MessageGroupBatch {
    val metadataProperties = request.run { mapOf("method" to method, "uri" to uri.toString(), "uuid" to id) }
    return ByteArrayOutputStream().run {
        startLine.writeTo(this)
        headers.writeTo(this)
        body.ifPresent { it.writeTo(this) }
        toBatch(connectionId, direction, sequence, metadataProperties)
    }
}

private fun HttpMessage.toBatch(connectionId: ConnectionID, direction: Direction, sequence: Long, request: RawHttpRequest): MessageGroupBatch {
    val metadataProperties = request.run { mapOf("method" to method, "uri" to uri.toString()) }
    return ByteArrayOutputStream().run {
        startLine.writeTo(this)
        headers.writeTo(this)
        body.ifPresent { it.writeTo(this) }
        toBatch(connectionId, direction, sequence, metadataProperties)
    }
}

fun RawHttpRequest.toBatch(connectionId: ConnectionID, sequence: Long, id: String): MessageGroupBatch = toBatch(connectionId, FIRST, sequence, this, id)
//fun RawHttpResponse<*>.toBatch(connectionId: ConnectionID, sequence: Long, request: RawHttpRequest): MessageGroupBatch = toBatch(connectionId, FIRST, sequence, request)