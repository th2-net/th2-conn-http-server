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

package com.exactpro.th2.http.server.testimpl

import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.message
import com.exactpro.th2.http.client.HttpClient
import com.exactpro.th2.http.server.HttpServer
import com.exactpro.th2.http.server.util.ResponseBuilder
import com.google.protobuf.ByteString
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.fail
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpResponse
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val LOGGER = KotlinLogging.logger { }

open class TestServerManager(private val https: Boolean = false, socketDelayCheck: Long = 15) {
    private val options = TestServerOptions(https)
    private val th2server = HttpServer(options, 5, socketDelayCheck)

    val response = { uuid: String ->
        val responseMessage = message("Response", Direction.FIRST, "somealias").apply {
            addField("code", 200)
            addField("reason", "Test reason")
            parentEventId = EventID.getDefaultInstance()
            metadataBuilder.protocol = "http"
            metadataBuilder.putProperties("uuid", uuid)
            if (!https) {
                addField("headers", listOf(
                    message().apply {
                        addField("name", "Connection")
                        addField("value", "close")
                    },
                    message().apply {
                        addField("name", "Content-Length")
                        addField("value", "0")
                    }
                ))
            }
        }.build()

        val bodyMessage = RawMessage.newBuilder().apply {
            parentEventId = EventID.getDefaultInstance()
            body = ByteString.copyFrom("SOME BYTES".toByteArray())
            metadata = metadataBuilder.putProperties("contentType", "application").build()
        }.build()

        ResponseBuilder().setHead(responseMessage).setBody(bodyMessage).build()
    }

    fun start() {
        if (https) {
            val truststore: String = File(this::class.java.classLoader.getResource("TestTrustStore")!!.file).absolutePath
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

    private fun handleResponse() {
        val th2response = response(options.queue.take())
        th2server.handleResponse(th2response)
    }

    fun stressSpam(request: String, requestsCount: Int = 5) {
        val executor: ExecutorService = Executors.newCachedThreadPool()

        val client = HttpClient(
            https,
            "localhost",
            25565,
            20000,
            5000,
            minOf(requestsCount, 5),
            emptyMap(),
            { inputRequest -> inputRequest },
            { LOGGER.info { "Client hook: Sending request" } },
            { _, response -> LOGGER.info { "Client hook: Received response: ${response.statusCode}" } }
        )

        try {

            val futures = (0 until requestsCount).toList().map {
                executor.submit(Callable<RawHttpResponse<*>> {
                    client.send(RawHttp().parseRequest(request))
                })
            }

            repeat(requestsCount) {
                handleResponse()
                LOGGER.debug { "Server handled response number: ${it+1}" }
            }

            futures.forEachIndexed { index, future ->
                future.runCatching {
                    val response = get(5, TimeUnit.SECONDS)
                    LOGGER.debug { "[${index + 1}] Feature returned response: $response" }
                    Assertions.assertEquals(response.statusCode, 200)
                    LOGGER.debug { "${index + 1} test passed" }
                }.onFailure {
                    fail("Can't get response ${index + 1}", it)
                }
            }
        } catch (e: Exception) {
            LOGGER.error(e) { "Can't handle stress test " }
            fail("Can't handle stress test with max: $requestsCount", e)
        } finally {
           client.close()
        }
    }

}