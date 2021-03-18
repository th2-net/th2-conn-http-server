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

import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.message
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.google.protobuf.ByteString
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

private val LOGGER = KotlinLogging.logger { }

class TestTh2Response {

    @Test
    fun th2ResponseTest() {
        val responseMessage = message("Response", Direction.FIRST, "somealias").apply {
            addField("code", 500)
            addField("reason", "Test reason")
            addField("TestFieldThree", "Three")
            //addField("headers",)
            metadataBuilder.protocol = "http"
            metadataBuilder.putProperties("uuid", "test-uuid")
        }.build()

        val bodyMessage = RawMessage.newBuilder().apply {
            body = ByteString.copyFrom("SOME BYTES".toByteArray())
            metadata = metadataBuilder.putProperties("contentType", "application").build()
        }.build()

        val response = Th2Response.Builder().setHead(responseMessage).setBody(bodyMessage).build()
        Assertions.assertEquals(500, response.statusCode)
        Assertions.assertEquals("Test reason", response.startLine.reason)
        Assertions.assertEquals("test-uuid", response.uuid)
        Assertions.assertEquals("application", response.headers["content-type"][0])


    }
}