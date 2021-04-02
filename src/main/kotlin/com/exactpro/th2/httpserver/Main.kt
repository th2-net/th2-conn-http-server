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
import com.exactpro.th2.httpserver.api.IResponseManager
import com.exactpro.th2.httpserver.api.IResponseManager.ResponseManagerContext
import com.exactpro.th2.httpserver.api.impl.BasicResponseManager
import com.exactpro.th2.httpserver.server.Th2HttpServer
import com.exactpro.th2.httpserver.server.options.ServerOptions
import com.exactpro.th2.httpserver.server.options.Th2ServerOptions
import com.exactpro.th2.httpserver.util.toPrettyString
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import mu.KotlinLogging
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val LOGGER = KotlinLogging.logger { }

class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) = try {
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

            val responseManager = load<IResponseManager>(BasicResponseManager::class.java)

            val mapper = JsonMapper.builder()
                .addModule(KotlinModule(nullIsSameAsDefault = true))
                .build()

            val settings = factory.getCustomConfiguration(Settings::class.java, mapper)

            run(
                settings,
                responseManager,
                factory.eventBatchRouter,
                factory.messageRouterMessageGroupBatch
            ) { resource, destructor ->
                resources += resource to destructor
            }
        } catch (e: Exception) {
            LOGGER.error(e) { "Uncaught exception. Shutting down" }
            exitProcess(1)
        }

        private fun run(
            settings: Settings,
            responseManager: IResponseManager,
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

            val options = Th2ServerOptions(settings.https, settings.port, settings.threads, connectionId, messageRouter)
            val eventStore = { name: String, type: String, error: Throwable? ->
                eventRouter.storeEvent(
                    rootEventId,
                    name,
                    type,
                    error
                )
            }


            val listener = MessageListener<MessageGroupBatch> { _, message ->
                message.groupsList.forEach { group ->
                    group.runCatching(responseManager::handleResponse).recoverCatching {
                        LOGGER.error(it) { "Failed to handle message group: ${group.toPrettyString()}" }
                        eventStore("Failed to handle message group: ${group.toPrettyString()}", "Error", it)
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

            val server = Th2HttpServer(eventStore, options, settings.terminationTime).apply {
                registerResource("server", ::stop)
            }

            responseManager.runCatching {
                registerResource("response-manager", ::close)
                init(ResponseManagerContext(server::handleResponse))
            }.onFailure {
                LOGGER.error(it) { "Failed to init response-manager" }
                eventStore("Failed to init response-manager", "Error", it)
                throw it
            }

            server.start()
        }

        data class Settings(
            val port: Int = 80,
            val sessionAlias: String,
            val threads: Int = 24,
            val https: Boolean = false,
            val terminationTime: Long = 30
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
