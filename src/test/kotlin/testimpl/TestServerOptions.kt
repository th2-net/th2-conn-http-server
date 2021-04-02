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

package testimpl

import com.exactpro.th2.httpserver.server.options.ServerOptions
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.io.FileInputStream

import java.security.KeyStore
import javax.net.ServerSocketFactory
import javax.net.ssl.*


private val logger = KotlinLogging.logger {}

class TestServerOptions(private val https: Boolean = false) : ServerOptions {
    var queue = ArrayBlockingQueue<String>(100)



    override fun createSocket(): ServerSocket {
        if (https) {
            return SSLServerSocketFactory.getDefault().createServerSocket(GlobalVariables.PORT) as SSLServerSocket
        }
        return ServerSocketFactory.getDefault().createServerSocket(GlobalVariables.PORT).apply { logger.info("Created server socket on port:${GlobalVariables.PORT}") }
    }

    override fun createExecutorService(): ExecutorService {
        val threadCount = AtomicInteger(1)
        return Executors.newFixedThreadPool(12) { runnable: Runnable? ->
            Thread(runnable).apply {
                isDaemon = true
                name = "th2-http-server-${threadCount.incrementAndGet()}"
            }
        }
    }

    override fun onRequest(request: RawHttpRequest, id: String) {
        queue.add(id)
    }

}