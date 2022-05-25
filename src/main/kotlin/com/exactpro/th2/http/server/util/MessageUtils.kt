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

@file:JvmName("MessageUtil")

package com.exactpro.th2.http.server.util

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.Direction.FIRST
import com.exactpro.th2.common.grpc.Direction.SECOND
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.toTimestamp
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite.Builder
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.io.ByteArrayOutputStream
import java.time.Instant

private inline operator fun <T : Builder> T.invoke(block: T.() -> Unit) = apply(block)

fun MessageOrBuilder.toPrettyString(): String = JsonFormat.printer().omittingInsignificantWhitespace().includingDefaultValueFields().print(this)

fun Message.requireType(type: String): Message = apply {
    check(metadata.messageType == type) { "Invalid message type: ${metadata.messageType} (expected: $type)" }
}

fun AnyMessage.toParsed(name: String): Message = run {
    require(hasMessage()) { "$name is not a parsed message: ${toPrettyString()}" }
    message
}

fun AnyMessage.toRaw(name: String): RawMessage = run {
    require(hasRawMessage()) { "$name is not a raw message: ${toPrettyString()}" }
    rawMessage
}

fun RawMessage.toBatch(): MessageGroupBatch = run(AnyMessage.newBuilder()::setRawMessage)
    .run(MessageGroup.newBuilder()::addMessages)
    .run(MessageGroupBatch.newBuilder()::addGroups)
    .build()

private fun ByteArrayOutputStream.toRawMessage(
    connectionId: ConnectionID,
    direction: Direction,
    sequence: Long,
    metadataProperties: Map<String, String>,
    eventId: String?
) = RawMessage.newBuilder().apply {
    this.body = ByteString.copyFrom(toByteArray())
    eventId?.let {
        this.parentEventIdBuilder.id = it
    }
    this.metadataBuilder {
        putAllProperties(metadataProperties)
        this.timestamp = Instant.now().toTimestamp()
        this.idBuilder {
            this.connectionId = connectionId
            this.direction = direction
            this.sequence = sequence
        }
    }
}.build()

private fun RawHttpRequest.toRawMessage(connectionId: ConnectionID, direction: Direction, sequence: Long, request: RawHttpRequest, uuid: String, eventId: String): RawMessage {
    val metadataProperties = request.run { mapOf("method" to method, "uri" to uri.toString(), "uuid" to uuid) }
    return ByteArrayOutputStream().run {
        startLine.writeTo(this)
        headers.writeTo(this)
        body.ifPresent { it.writeTo(this) }
        toRawMessage(connectionId, direction, sequence, metadataProperties, eventId)
    }
}

private fun RawHttpResponse<LinkedData>.toRawMessage(connectionId: ConnectionID, direction: Direction, sequence: Long): RawMessage {
    val metadataProperties = mapOf("http" to startLine.httpVersion.toString(), "code" to startLine.statusCode.toString(), "reason" to startLine.reason)
    val eventId = this.libResponse.get().eventId.id
    return ByteArrayOutputStream().run {
        startLine.writeTo(this)
        headers.writeTo(this)
        body.ifPresent { it.writeTo(this) }
        toRawMessage(connectionId, direction, sequence, metadataProperties, eventId)
    }
}

fun RawHttpRequest.toRawMessage(connectionId: ConnectionID, sequence: Long, uuid: String, eventId: String): RawMessage = toRawMessage(connectionId, SECOND, sequence, this, uuid, eventId)
fun RawHttpResponse<LinkedData>.toRawMessage(connectionId: ConnectionID, sequence: Long): RawMessage = toRawMessage(connectionId, FIRST, sequence)

fun MessageGroup.getFirstParentEventID() = messagesList.firstNotNullOfOrNull {
    when {
        it.hasRawMessage() -> it.rawMessage.parentEventId?.id
        it.hasMessage() -> it.message.parentEventId?.id
        else -> error("Cannot handle message kind: ${it.kindCase}")
    }
}

fun MessageGroup.getMessageIDs() = messagesList.mapNotNull {
    when {
        it.hasRawMessage() -> it.rawMessage.metadata?.id
        it.hasMessage() -> it.message.metadata?.id
        else -> error("Cannot handle message kind: ${it.kindCase}")
    }
}

fun MessageGroup.getParentEventId() = messagesList.mapNotNull {
    when {
        it.hasRawMessage() -> it.rawMessage.parentEventId?.id
        it.hasMessage() -> it.message.parentEventId?.id
        else -> error("Cannot handle message kind: ${it.kindCase}")
    }
}.toSet()