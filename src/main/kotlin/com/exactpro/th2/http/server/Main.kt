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

package com.exactpro.th2.http.server

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.plusAssign
import com.exactpro.th2.common.schema.factory.CommonFactory
import com.exactpro.th2.common.schema.message.MessageListener
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.common.schema.message.storeEvent
import com.exactpro.th2.http.server.api.IStateManager
import com.exactpro.th2.http.server.api.IStateManager.StateManagerContext
import com.exactpro.th2.http.server.api.IStateManagerSettings
import com.exactpro.th2.http.server.api.impl.BasicStateManager
import com.exactpro.th2.http.server.options.Th2ServerOptions
import com.exactpro.th2.http.server.util.createErrorEvent
import com.exactpro.th2.http.server.util.getFirstParentEventID
import com.exactpro.th2.http.server.util.getMessageIDs
import com.exactpro.th2.http.server.util.getParentEventId
import com.exactpro.th2.http.server.util.toResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import java.util.ServiceLoader

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

            val stateManager = load<IStateManager>(BasicStateManager::class.java).apply { resources += "state-manager" to ::close }

            val mapper = ObjectMapper(YAMLFactory()).apply {
                registerModule(KotlinModule(nullIsSameAsDefault = true))
                registerModule(SimpleModule().addAbstractTypeMapping(IStateManagerSettings::class.java, stateManager.settingsClass))
                configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
            }

            val settings = factory.getCustomConfiguration(MicroserviceSettings::class.java, mapper)

            run(
                settings,
                stateManager,
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
            settings: MicroserviceSettings,
            stateManager: IStateManager,
            eventRouter: MessageRouter<EventBatch>,
            messageRouter: MessageRouter<MessageGroupBatch>,
            registerResource: (name: String, destructor: () -> Unit) -> Unit
        ) {

            val rootEventId = eventRouter.storeEvent(Event.start().apply {
                endTimestamp()
                name("HTTP SERVER | alias: \"${settings.sessionAlias}\" | ${Instant.now()}")
                type("Microservice")
            }).id

            val eventStore = { event: Event, eventId: String? -> eventRouter.storeEvent(event, eventId ?: rootEventId).id }

            val options = Th2ServerOptions(
                settings,
                stateManager,
                { messageRouter.send(it, QueueAttribute.SECOND.toString()) },
                { messageRouter.send(it, QueueAttribute.FIRST.toString()) },
                eventStore
            )

            val server = HttpServer(options, settings.terminationTime, settings.socketDelayCheck).apply {
                registerResource("server", ::stop)
            }

            val listener = MessageListener<MessageGroupBatch> { _, batch ->
                for (messageGroup in batch.groupsList) {
                    val response = try {
                        messageGroup.toResponse()
                    } catch (e: Exception) {
                        LOGGER.error(e) { "Can't parse message group to response" }
                        messageGroup.getParentEventId().forEach { parentEventID ->
                            eventStore(
                                createErrorEvent("Can't parse message group to response", e, messageGroup.getMessageIDs()),
                                parentEventID
                            )
                        }
                        continue
                    }

                    response.runCatching(server::handleResponse).onFailure {
                        val uuid = response.libResponse.get().uuid
                        LOGGER.error(it) { "Can't handle response with uuid: $uuid" }
                        eventStore(
                            createErrorEvent("Can't handle response with uuid: $uuid", it, messageGroup.getMessageIDs()),
                            messageGroup.getFirstParentEventID()
                        )
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

            stateManager.runCatching {
                registerResource("state-manager", ::close)
                val stateManagerEvent =  eventStore(
                    Event.start().endTimestamp().name("State manager"),
                    rootEventId
                )
                init(StateManagerContext(server, stateManagerEvent, eventStore), settings.customSettings)
            }.onFailure {
                LOGGER.error(it) { "Failed to init state-manager" }
                eventStore(
                    createErrorEvent("Failed to init state-manager", it),
                    rootEventId
                )
                throw it
            }.getOrThrow()

            server.start()
        }

        data class MicroserviceSettings(
            val https: Boolean = false,
            val port: Int? = if (https) 443 else 80,
            val sessionAlias: String,
            val threads: Int = 24,
            val terminationTime: Long = 30,
            val socketDelayCheck: Long = 15,
            val serverSocketTimeout: Int? = null,
            val clientSocketTimeout: Int? = null,
            val sslProtocol: String = "TLSv1.3",
            val keystorePass: String = "",
            val keystorePath: String = "",
            val keystoreType: String = "JKS",
            val keyManagerAlgorithm: String = "SunX509",
            val catchClientClosing: Boolean = true,
            val customSettings: IStateManagerSettings?
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

        private fun MessageRouter<MessageGroupBatch>.send(message: RawMessage, vararg attribute: String) {
            sendAll(MessageGroupBatch.newBuilder().addGroups(MessageGroup.newBuilder().apply {
                this += message
            }).build(), *attribute)
        }

    }

}
