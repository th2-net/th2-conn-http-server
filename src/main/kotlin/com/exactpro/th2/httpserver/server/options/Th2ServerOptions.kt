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

package com.exactpro.th2.httpserver.server.options

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.httpserver.Main.Companion.Settings
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.exactpro.th2.httpserver.util.toBatch
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.io.File
import java.net.ServerSocket
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
    private val settings: Settings,
    private val connectionID: ConnectionID,
    private val messageRouter: MessageRouter<MessageGroupBatch>
) : ServerOptions {

    private val socketFactory: ServerSocketFactory = createFactory()

    private val logger = KotlinLogging.logger {}


    private val generateSequenceRequest = sequenceGenerator()
    private val generateSequenceResponse = sequenceGenerator()

    override fun createSocket(): ServerSocket {
        return socketFactory.createServerSocket(settings.port).apply { logger.info("Created server socket on port:${settings.port}") }
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
        val threadCount = AtomicInteger(1)
        return Executors.newFixedThreadPool(settings.threads) { runnable: Runnable? ->
            Thread(runnable).apply {
                isDaemon = true
                name = "th2-http-server-${threadCount.incrementAndGet()}"
            }
        }
    }

    override fun onRequest(request: RawHttpRequest, id: String) {
        val event = Event.start().endTimestamp().toProto(null)
        messageRouter.sendAll(
            request.toBatch(connectionID, generateSequenceRequest(), id, event.id),
            QueueAttribute.SECOND.toString()
        )

    }

    override fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<Th2Response>): RawHttpResponse<Th2Response> {
        return response
    }

    override fun onResponse(response: RawHttpResponse<Th2Response>) {
        messageRouter.sendAll(
            response.toBatch(connectionID, generateSequenceResponse()),
            QueueAttribute.FIRST.toString()
        )
    }

    private fun sequenceGenerator() = Instant.now().run {
        AtomicLong(epochSecond * TimeUnit.SECONDS.toNanos(1) + nano)
    }::incrementAndGet
}