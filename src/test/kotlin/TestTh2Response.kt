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

import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.message
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.google.protobuf.ByteString
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class TestTh2Response {

    @Test
    fun contentLengthTest() {
        // Auto generation of content length and content type overwrite check
        val response = Th2Response.Builder().setHead(
            createHeadMessage(
                500, uuid = "test-uuid",
                reason = "Test reason"
            )
        ).setBody(
            createBodyMessage(
                body = "SOME BYTES".toByteArray(),
                contentType = "application"
            )
        ).build()

        Assertions.assertTrue(response.libResponse.isPresent)
        Assertions.assertEquals(500, response.statusCode)
        Assertions.assertEquals("Test reason", response.startLine.reason)
        Assertions.assertEquals("test-uuid", response.libResponse.get().uuid)
        Assertions.assertEquals("application", response.headers["content-type"][0])
        Assertions.assertEquals("10", response.headers["content-length"][0])
    }

    @Test
    fun overwriteTest() {
        val response = Th2Response.Builder().setHead(
            createHeadMessage(
                404, uuid = "0-0-0-0-0",
                reason = "Non",
                contentLength = 5
            )
        ).setBody(
            createBodyMessage(
                body = "SOME BYTES".toByteArray()
            )
        ).build()

        Assertions.assertTrue(response.libResponse.isPresent)
        Assertions.assertEquals(404, response.statusCode)
        Assertions.assertEquals("Non", response.startLine.reason)
        Assertions.assertEquals("0-0-0-0-0", response.libResponse.get().uuid)
        Assertions.assertEquals(emptyList<String>(), response.headers["content-type"])
        Assertions.assertEquals("5", response.headers["content-length"][0])
    }

    @Test
    fun autoGenerationTest() {
        // Auto generation check
        val response = Th2Response.Builder().setHead(
            createHeadMessage(
                uuid = "0-0-0-0-0",
                contentLength = 4
            )
        ).setBody(
            createBodyMessage(
                body = "SOME BYTES".toByteArray(),
            )
        ).build()

        Assertions.assertTrue(response.libResponse.isPresent)
        Assertions.assertEquals(200, response.statusCode)
        Assertions.assertEquals("OK", response.startLine.reason)
        Assertions.assertEquals("0-0-0-0-0", response.libResponse.get().uuid)
        Assertions.assertEquals(emptyList<String>(), response.headers["content-type"])
        Assertions.assertEquals("4", response.headers["content-length"][0])
    }

    @Test
    fun th2ErrorTest() {
        val stateException = IllegalStateException().javaClass
        val argumentException = IllegalArgumentException().javaClass

        // Test for UUID required
        Assertions.assertThrows(
            stateException,
            { Th2Response.Builder().setHead(createHeadMessage()).build() },
            "UUID must be non null, Th2Response must throw error"
        )

        // Test for Response header message type required
        Assertions.assertThrows(
            stateException,
            { Th2Response.Builder().setHead(createHeadMessage(uuid = "0-0-0-0-0", headType = "WrongType")) },
            "Type of head message must be response, Th2Response must throw error if there another type of message"
        )

        // Test in group for Response header message type required
        Assertions.assertThrows(
            stateException,
            {
                Th2Response.Builder().setGroup(
                    MessageGroup.newBuilder()
                        .addMessages(
                            AnyMessage.newBuilder()
                                .mergeMessage(createHeadMessage(uuid = "0-0-0-0-0", headType = "WrongType")).build()
                        )
                        .addMessages(AnyMessage.newBuilder().mergeRawMessage(createBodyMessage()).build())
                        .build()
                )
            },
            "Type of head message must be response, Th2Response must throw error if there another type of message"
        )

        // Test for message type check in the group, raw or parsed
        Assertions.assertThrows(
            argumentException,
            {
                Th2Response.Builder().setGroup(
                    MessageGroup.newBuilder()
                        .addMessages(AnyMessage.newBuilder().mergeRawMessage(createBodyMessage()).build())
                        .addMessages(
                            AnyMessage.newBuilder()
                                .mergeMessage(createHeadMessage(uuid = "0-0-0-0-0", headType = "WrongType")).build()
                        )
                        .build()
                )
            },
            "Type of head message must be response, Th2Response must throw error if there another type of message"
        )


    }

    private fun createHeadMessage(
        code: Int? = null,
        reason: String? = null,
        uuid: String? = null,
        contentLength: Int? = null,
        headType: String = "Response",
    ): Message {
        return message(headType, Direction.FIRST, "test").apply {
            code?.let { this.addField("code", code) }
            reason?.let { this.addField("reason", reason) }
            this.metadataBuilder.protocol = "http"
            if (contentLength != null) {
                this.addField(
                    "headers", listOf(
                        message("header").apply {
                            this.addField("name", "Content-Length")
                            this.addField("value", contentLength)
                        }.build()
                    )
                )
            }
            uuid?.let { this.metadataBuilder.putProperties("uuid", it) }

        }.build()
    }

    private fun createBodyMessage(
        body: ByteArray = "".toByteArray(),
        contentType: String? = null,
    ): RawMessage {
        return RawMessage.newBuilder().apply {
            this.body = ByteString.copyFrom(body)
            if (contentType != null) this.metadata = metadataBuilder.putProperties("contentType", contentType).build()

        }.build()
    }

}