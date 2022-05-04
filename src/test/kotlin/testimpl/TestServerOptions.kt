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

import com.exactpro.th2.http.server.options.ServerOptions
import com.exactpro.th2.http.server.response.CommonData
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import java.security.KeyStore
import javax.net.ServerSocketFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext


class TestServerOptions(private val https: Boolean = false) : ServerOptions() {
    var queue = ArrayBlockingQueue<String>(100)

    override fun createSocket(): ServerSocket {
        return getServerSocketFactory().createServerSocket(GlobalVariables.PORT)
            .apply { logger.info("Created server socket on port:${GlobalVariables.PORT}") }
    }

    private fun getServerSocketFactory(): ServerSocketFactory {
        if (https) {
            val passphrase = "servertest".toCharArray()
            val ctx: SSLContext = SSLContext.getInstance("TLSv1.3")
            val kmf: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
            val ks: KeyStore = KeyStore.getInstance("JKS")
            this::class.java.classLoader.getResourceAsStream("servertest").use { ks.load(it, passphrase) }
            kmf.init(ks, passphrase)
            ctx.init(kmf.keyManagers, null, null)
            return ctx.serverSocketFactory
        }
        return ServerSocketFactory.getDefault()
    }

    override fun createExecutorService(): ExecutorService {
        val threadCount = AtomicInteger(1)
        return Executors.newFixedThreadPool(GlobalVariables.THREADS) { runnable: Runnable? ->
            Thread(runnable).apply {
                isDaemon = true
                name = "th2-http-server-${threadCount.incrementAndGet()}"
                logger.info { "Thread created: $name" }
            }
        }
    }

    override fun onRequest(request: RawHttpRequest, uuid: String, parentEventID: String) {
        queue.add(uuid)
        logger.debug { "OnRequest options call" }
    }

    override fun onResponse(response: RawHttpResponse<CommonData>) {
        logger.debug { "onResponse options call" }
    }

    override fun onConnect(client: Socket): String {
        logger.debug { "onConnect options call" }
        return "TestEventID"
    }

}