package tech.caen.pimanager.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import tech.caen.pimanager.model.StreamEvent

/**
 * Bus d'événements temps réel. Le poller et les actions y publient ;
 * les clients WS/SSE s'y abonnent.
 */
class EventBus {
    private val _events = MutableSharedFlow<StreamEvent>(replay = 0, extraBufferCapacity = 128)
    val events: SharedFlow<StreamEvent> = _events

    fun publish(event: StreamEvent) {
        _events.tryEmit(event)
    }
}
