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

package com.exactpro.th2.http.server.options

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.http.server.Main.Companion.MicroserviceSettings
import com.exactpro.th2.http.server.response.CommonData
import com.exactpro.th2.http.server.util.toRawMessage
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ServerSocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class Th2ServerOptions(
    private val settings: MicroserviceSettings,
    private val connectionID: ConnectionID,
    private val onRequest: (message: RawMessage) -> Unit,
    private val onResponse: (message: RawMessage) -> Unit,
    private val onEvent: (event: Event, parentEventID: String?) -> String,
) : ServerOptions() {

    private val socketFactory: ServerSocketFactory = createFactory()

    private val generateSequenceRequest = sequenceGenerator()
    private val generateSequenceResponse = sequenceGenerator()

    override fun createSocket(): ServerSocket {
        val port = settings.port ?: if (settings.https) 443 else 80
        return socketFactory.createServerSocket(port)
            .apply { logger.info("Created server socket on port:$port") }
    }

    private fun createFactory(): ServerSocketFactory {
        if (settings.https) {
            val passphrase = settings.keystorePass.toCharArray()
            val ctx: SSLContext = SSLContext.getInstance(settings.sslProtocol)
            val kmf: KeyManagerFactory = KeyManagerFactory.getInstance(settings.keyManagerAlgorithm)
            val ks: KeyStore = KeyStore.getInstance(settings.keystoreType)
            if (settings.keystorePath.isEmpty()) {
                this::class.java.classLoader.getResourceAsStream("defaultkeystore").use { ks.load(it, passphrase) }
            } else {
                File(settings.keystorePath).inputStream().use {
                    ks.load(it, passphrase)
                }
            }

            kmf.init(ks, passphrase)
            ctx.init(kmf.keyManagers, null, null)
            return ctx.serverSocketFactory
        }
        return ServerSocketFactory.getDefault()
    }

    override fun createExecutorService(): ExecutorService {
        logger.trace { "Created Executor service" }
        val threadCount = AtomicInteger(1)
        return Executors.newFixedThreadPool(settings.threads) { runnable: Runnable? ->
            Thread(runnable).apply {
                isDaemon = true
                name = "th2-http-server-${threadCount.incrementAndGet()}"
                logger.trace { "Created thread: $name" }
            }
        }
    }

    override fun onRequest(request: RawHttpRequest, uuid: String, parentEventID: String) {
        logger.trace { "Trying to send request into mq: ${request.startLine}" }

        val rawMessage = request.toRawMessage(connectionID, generateSequenceRequest(), uuid, parentEventID)

        onRequest(rawMessage)

        storeEvent("Received HTTP request", parentEventID, uuid, listOf(rawMessage.metadata.id))

        logger.info { "HTTP request was sent to mq, parentEventID: $parentEventID | uuid: $uuid" }
    }


    override fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<CommonData>) = response

    override fun onResponse(response: RawHttpResponse<CommonData>) {
        val rawMessage = response.toRawMessage(connectionID, generateSequenceResponse())

        onResponse(rawMessage)

        val th2Response = response.libResponse.get()
        val eventId = storeEvent("Sent HTTP response", th2Response.eventId.id, th2Response.uuid, th2Response.messagesId)
        logger.info { "$eventId: Sent HTTP response: \n$response" }
    }

    override fun onConnect(client: Socket): String {
        val msg = "Connected client: $client"
        val eventId = storeEvent(msg, null, null)
        logger.info { "$msg | parentEventID: $eventId" }
        return eventId
    }

    private fun sequenceGenerator() = Instant.now().run {
        AtomicLong(epochSecond * TimeUnit.SECONDS.toNanos(1) + nano)
    }::incrementAndGet

    private fun storeEvent(name: String, eventId: String?, uuid: String?, messagesId: List<MessageID>? = null): String {
        val type = if (uuid != null) "Info" else "Connection"
        val status = Event.Status.PASSED
        val event = Event.start().apply {
            endTimestamp()
            name(name)
            type(type)
            status(status)

            uuid?.let { bodyData(EventUtils.createMessageBean("UUID: $it")) }
            messagesId?.forEach(this::messageID)
        }

        return onEvent(event, eventId)
    }

    override fun onError(message: String, clientID: String?, exception: Throwable) {
        val event = Event.start().apply {
            endTimestamp()
            name(message)
            type("Error")
            status(Event.Status.FAILED )

            var error: Throwable? = exception

            while (error != null) {
                bodyData(EventUtils.createMessageBean(error.message))
                error = error.cause
            }
        }

        onEvent(event, clientID)
    }
}