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

package com.exactpro.th2.httpserver

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.ConnectionID
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.httpserver.api.IRequestHandler
import com.exactpro.th2.httpserver.api.IResponseHandler
import com.exactpro.th2.httpserver.api.IResponseManager
import com.exactpro.th2.httpserver.api.impl.BasicRequestHandler
import com.exactpro.th2.httpserver.api.impl.BasicResponseHandler
import com.exactpro.th2.httpserver.api.impl.BasicResponseManager
import com.exactpro.th2.httpserver.server.Th2HttpServer
import com.exactpro.th2.httpserver.server.options.ServerOptions
import com.exactpro.th2.httpserver.server.options.Th2ServerOptions
import com.exactpro.th2.httpserver.util.toPrettyString
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.concurrent.thread

private val LOGGER = KotlinLogging.logger { }

class Main {
    companion object {
        @JvmStatic fun main(args: Array<String>) = try {
            val resources = ConcurrentLinkedDeque<Pair<String, () -> Unit>>()

            Runtime.getRuntime().addShutdownHook(thread(start = false, name = "shutdown-hook") {
                resources.descendingIterator().forEach { (resource, destructor) ->
                    LOGGER.debug { "Destroying resource: $resource" }
                    runCatching(destructor).apply {
                        onSuccess { LOGGER.debug { "Successfully destroyed resource: $resource" } }
                        onFailure { LOGGER.error(it) { "Failed to destroy resource: $resource" } }
                    }
                }
            })

            val factory = args.runCatching(CommonFactory::createFromArguments).getOrElse {
                LOGGER.error(it) { "Failed to create common factory with arguments: ${args.joinToString(" ")}" }
                CommonFactory()
            }.apply { resources += "factory" to ::close }

            val requestHandler = load<IRequestHandler>(BasicRequestHandler::class.java)
            val responseHandler = load<IResponseHandler>(BasicResponseHandler::class.java)
            val responseManager = load<IResponseManager>(BasicResponseManager::class.java)

            val mapper = JsonMapper.builder()
                .addModule(KotlinModule(nullIsSameAsDefault = true))
                .build()

            val settings = factory.getCustomConfiguration(Settings::class.java, mapper)

            run(
                settings, responseManager, responseHandler, requestHandler,
                factory.eventBatchRouter, factory.messageRouterMessageGroupBatch
            ) { resource, destructor ->
                resources += resource to destructor
            }
        } catch (e: Exception) {
            println("${e.message}")
            e.printStackTrace()
        }

        fun run(
            settings: Settings,
            responseManager: IResponseManager,
            responseHandler: IResponseHandler,
            requestHandler: IRequestHandler,
            eventRouter: MessageRouter<EventBatch>,
            messageRouter: MessageRouter<MessageGroupBatch>,
            registerResource: (name: String, destructor: () -> Unit) -> Unit
        ) {
            val connectionId = ConnectionID.newBuilder().setSessionAlias(settings.sessionAlias).build()

            val rootEventId = eventRouter.storeEvent(Event.start().apply {
                endTimestamp()
                name("HTTP server '${settings.sessionAlias}' ${Instant.now()}")
                type("Microservice")
            }).id

            val options: ServerOptions = Th2ServerOptions(settings.port,
                { req: RawHttpRequest, res: RawHttpResponse<*> -> responseHandler.onResponse(req, res) }
            ) { req: RawHttpRequest -> requestHandler.onRequest(req) }

            responseManager.runCatching {
                registerResource("response-manager", ::close)
                init(IResponseManager.ResponseManagerContext(connectionId, messageRouter))
            }.onFailure {
                LOGGER.error(it) { "Failed to init response-manager" }
                eventRouter.storeEvent(rootEventId, "Failed to init response-manager", "Error", it)
                throw it
            }

            responseHandler.runCatching {
                registerResource("response-handler", ::close)
                init()
            }.onFailure {
                LOGGER.error(it) { "Failed to init response-handler" }
                eventRouter.storeEvent(rootEventId, "Failed to init response-handler", "Error", it)
                throw it
            }

            requestHandler.runCatching {
                registerResource("request-handler", ::close)
                init()
            }.onFailure {
                LOGGER.error(it) { "Failed to init request-handler" }
                eventRouter.storeEvent(rootEventId, "Failed to init request-handler", "Error", it)
                throw it
            }

            val listener = MessageListener<MessageGroupBatch> { _, message ->
                message.groupsList.forEach { group ->
                    group.runCatching(responseManager::handleResponse).recoverCatching {
                        LOGGER.error(it) { "Failed to handle message group: ${group.toPrettyString()}" }
                        eventRouter.storeEvent(rootEventId, "Failed to handle raw batch: ${group.toPrettyString()}", "Error", it)
                    }
                }
            }

            runCatching {
                checkNotNull(messageRouter.subscribe(listener, "send"))
            }.onSuccess { monitor ->
                registerResource("raw-monitor", monitor::unsubscribe)
            }.onFailure {
                throw IllegalStateException("Failed to subscribe to input queue", it)
            }

            val server: Th2HttpServer = Th2HttpServer(options).apply {
                this.start(responseManager)
                registerResource("server", ::stop)
            }

            while (server.isRunning()) {
                Thread.sleep(150L)
            }
        }

        data class Settings(
            val port: Int = 80,
            val sessionAlias: String
        )

        private inline fun <reified T> load(defaultImpl: Class<out T>): T {
            val instances = ServiceLoader.load(T::class.java).toList()

            return when (instances.size) {
                0 -> error("No instances of ${T::class.simpleName}")
                1 -> instances.first()
                2 -> instances.first { !defaultImpl.isInstance(it) }
                else -> error("More than 1 non-default instance of ${T::class.simpleName} has been found: $instances")
            }
        }

    }

}
