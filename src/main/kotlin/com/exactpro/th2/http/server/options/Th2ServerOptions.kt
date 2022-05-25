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

package com.exactpro.th2.http.server.options

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.MessageID
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.http.server.Main.Companion.MicroserviceSettings
import com.exactpro.th2.http.server.api.IStateManager
import com.exactpro.th2.http.server.util.LinkedData
import com.exactpro.th2.http.server.util.createErrorEvent
import com.exactpro.th2.http.server.util.toRawMessage
import mu.KotlinLogging
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpHeaders
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.errors.InvalidHttpRequest
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
    private val stateManager: IStateManager,
    private val onRequest: (message: RawMessage) -> Unit,
    private val onResponse: (message: RawMessage) -> Unit,
    private val onEvent: (event: Event, parentEventID: String?) -> String,
) : ServerOptions {

    private val socketFactory: ServerSocketFactory = createFactory()

    private val generateSequenceRequest = sequenceGenerator()
    private val generateSequenceResponse = sequenceGenerator()

    private val connectionID = ConnectionID.newBuilder().setSessionAlias(settings.sessionAlias).build()

    override fun createSocket(): ServerSocket {
        val port = settings.port ?: if (settings.https) 443 else 80
        return socketFactory.createServerSocket(port)
            .apply { logger.info("Created server socket on port:$port") }
    }

    private fun createFactory(): ServerSocketFactory {
        if (!settings.https) {
            return ServerSocketFactory.getDefault()
        }
        val passphrase = settings.keystorePass.toCharArray()
        val ctx: SSLContext = SSLContext.getInstance(settings.sslProtocol)
        val kmf: KeyManagerFactory = KeyManagerFactory.getInstance(settings.keyManagerAlgorithm)
        val ks: KeyStore = KeyStore.getInstance(settings.keystoreType)
        when {
            settings.keystorePath.isEmpty() -> this::class.java.classLoader.getResourceAsStream("defaultkeystore").use { ks.load(it, passphrase) }
            else -> File(settings.keystorePath).inputStream().use {
                ks.load(it, passphrase)
            }
        }

        kmf.init(ks, passphrase)
        ctx.init(kmf.keyManagers, null, null)
        return ctx.serverSocketFactory
    }

    override fun createExecutorService(): ExecutorService {
        logger.trace { "Created Executor service" }
        val threadCount = AtomicInteger(1)
        return Executors.newFixedThreadPool(settings.threads) { runnable: Runnable ->
            Thread(runnable).apply {
                isDaemon = true
                name = "th2-http-server-${threadCount.incrementAndGet()}"
                logger.trace { "Created thread: $name" }
            }
        }
    }

    override fun onRequest(request: RawHttpRequest, uuid: String, parentEventID: String) {
        logger.trace { "Trying to send request into mq: ${request.startLine}" }

        stateManager.onRequest(request, uuid)

        val rawMessage = request.toRawMessage(connectionID, generateSequenceRequest(), uuid, parentEventID)

        onRequest(rawMessage)

        storeEvent("Received HTTP request", parentEventID, uuid, listOf(rawMessage.metadata.id))

        logger.debug { "HTTP request was sent to mq, parentEventID: $parentEventID | uuid: $uuid" }
    }


    override fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<LinkedData>): RawHttpResponse<LinkedData> = stateManager.prepareResponse(request, response).run {
        when {
            !body.isPresent && RawHttp.responseHasBody(startLine, request.startLine) -> withHeaders(RawHttpHeaders.CONTENT_LENGTH_ZERO)
            else -> this
        }
    }

    override fun onResponse(response: RawHttpResponse<LinkedData>) {
        val rawMessage = response.toRawMessage(connectionID, generateSequenceResponse())

        onResponse(rawMessage)

        val th2Response = response.libResponse.get()
        val eventId = storeEvent("Sent HTTP response", th2Response.eventId.id, th2Response.uuid, th2Response.messagesId ?: emptyList())
        logger.debug { "$eventId: Sent HTTP response: \n$response" }
    }

    override fun onConnect(client: Socket): String {
        val msg = "Connected client: $client"
        val eventId = storeEvent(msg, null, null)
        logger.debug { "$msg | parentEventID: $eventId" }
        return eventId
    }

    private fun sequenceGenerator() = Instant.now().run {
        AtomicLong(epochSecond * TimeUnit.SECONDS.toNanos(1) + nano)
    }::incrementAndGet

    private fun storeEvent(name: String, eventId: String?, uuid: String?, messagesId: List<MessageID> = emptyList()): String {
        val type = if (uuid != null) "Info" else "Connection"
        val status = Event.Status.PASSED
        val event = Event.start().apply {
            endTimestamp()
            name(name)
            type(type)
            status(status)

            uuid?.let { bodyData(EventUtils.createMessageBean("UUID: $it")) }
            messagesId.forEach(this::messageID)
        }

        return onEvent(event, eventId)
    }

    override fun onError(message: String, exception: Throwable?, clientID: String?) {
        when (exception) {
            null -> {
                logger.error { message }
                onEvent(createErrorEvent(message), clientID)
            }
            is InvalidHttpRequest -> {
                if (!settings.catchClientClosing && exception.message=="No content" && exception.lineNumber == 0) {
                    return
                }
                val newMessage = "Client closed connection. $message."
                logger.error(exception) { newMessage }
                onEvent(createErrorEvent(newMessage, exception), clientID)
            }
            else -> {
                logger.error(exception) { message }
                onEvent(createErrorEvent(message, exception), clientID)
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger { Th2ServerOptions::class.simpleName }
    }
}