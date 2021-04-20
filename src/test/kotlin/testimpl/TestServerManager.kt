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
 *
 */

package testimpl

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.message
import com.exactpro.th2.httpserver.server.Th2HttpServer
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.google.protobuf.ByteString
import mu.KotlinLogging
import java.io.File

private val LOGGER = KotlinLogging.logger { }

open class TestServerManager(private val https: Boolean = false, socketDelayCheck: Long = 15) {
    private val options = TestServerOptions(https)
    private val th2server = Th2HttpServer({ _, _, _ -> Event.start() }, options, 1, socketDelayCheck)

    val response = { uuid: String ->
        val responseMessage = message("Response", Direction.FIRST, "somealias").apply {
            addField("code", 200)
            addField("reason", "Test reason")
            metadataBuilder.protocol = "http"
            metadataBuilder.putProperties("uuid", uuid)
        }.build().apply { LOGGER.debug { "Header message is created" } }

        val bodyMessage = RawMessage.newBuilder().apply {
            body = ByteString.copyFrom("SOME BYTES".toByteArray())
            metadata = metadataBuilder.putProperties("contentType", "application").build()
        }.build().apply { LOGGER.debug { "Body message is created" } }

        Th2Response.Builder().setHead(responseMessage).setBody(bodyMessage).build()
    }

    fun start() {
        if (https) {
            val truststore: String = File(this::class.java.classLoader.getResource("TestTrustStore").file).absolutePath
            val pass = "servertest"
            System.setProperty("javax.net.ssl.trustStore", truststore)
            System.setProperty("javax.net.ssl.trustStorePassword", pass)
            System.setProperty("javax.net.debug", "all")
        }

        this.th2server.start()
    }

    fun close() {
        this.th2server.stop()
    }

    fun handleResponse() {
        val th2response = response(options.queue.take())
        th2server.handleResponse(th2response)
    }

}