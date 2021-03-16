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

private fun HttpMessage.toBatch(connectionId: ConnectionID, direction: Direction, sequence: Long, request: RawHttpRequest, uuid: String): MessageGroupBatch {
    val metadataProperties = request.run { mapOf("method" to method, "uri" to uri.toString(), "uuid" to uuid) }
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

fun RawHttpRequest.toBatch(connectionId: ConnectionID, sequence: Long, id: String): MessageGroupBatch = toBatch(connectionId, SECOND, sequence, this, id)
fun RawHttpResponse<*>.toBatch(connectionId: ConnectionID, sequence: Long, request: RawHttpRequest): MessageGroupBatch = toBatch(connectionId, FIRST, sequence, request)