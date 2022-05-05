package com.exactpro.th2.http.server.util

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.MessageID

fun createErrorEvent(message: String, exception: Throwable, messageIDs: List<MessageID> = emptyList()): Event = Event.start().apply {
    endTimestamp()
    name(message)
    type("Error")
    status(Event.Status.FAILED )

    var error: Throwable? = exception

    while (error != null) {
        bodyData(EventUtils.createMessageBean(error.message))
        error = error.cause
    }

    messageIDs.forEach(this::messageID)
}