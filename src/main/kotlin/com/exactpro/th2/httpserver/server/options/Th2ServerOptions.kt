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

package com.exactpro.th2.httpserver.server.options

import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.httpserver.util.toBatch
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ServerSocketFactory
import java.io.FileInputStream
import java.lang.Exception

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

import javax.net.ssl.SSLServerSocketFactory


class Th2ServerOptions(
    private val https: Boolean,
    private val port: Int,
    private val threads: Int
) : ServerOptions {

    private val logger = KotlinLogging.logger {}

    override fun createSocket(): ServerSocket {
        return getFactory().createServerSocket(port).apply { logger.info("Created server socket on port:${port}") }
    }

    private fun getFactory(): ServerSocketFactory {
        if (!https) return ServerSocketFactory.getDefault()

        // set up key manager to do server authentication
        val ctx: SSLContext = SSLContext.getInstance("TLSv1.2")
        //val kmf: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
        //val ks: KeyStore = KeyStore.getInstance("JKS")
        //ks.load(FileInputStream("testkeys"), passphrase)
        //kmf.init(ks, passphrase)
        ctx.init(null, null, null)
        return ctx.serverSocketFactory

    }

    override fun createExecutorService(): ExecutorService {
        val threadCount = AtomicInteger(1)
        return Executors.newFixedThreadPool(threads) { runnable: Runnable? ->
            Thread(runnable).apply {
                isDaemon = true
                name = "th2-http-server-${threadCount.incrementAndGet()}"
            }
        }
    }
}