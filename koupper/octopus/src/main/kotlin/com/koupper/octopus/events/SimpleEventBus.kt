package com.koupper.octopus.events

class SimpleEventBus : EventBus {
    private val listeners = mutableMapOf<Class<*>, MutableList<DomainEventListener<*>>>()

    fun <E : DomainEvent> register(eventType: Class<E>, listener: DomainEventListener<E>) {
        listeners.computeIfAbsent(eventType) { mutableListOf() }.add(listener)
    }

    override fun publish(event: DomainEvent) {
        listeners[event::class.java]?.forEach { l ->
            @Suppress("UNCHECKED_CAST")
            (l as DomainEventListener<DomainEvent>).on(event)
        }
    }
}
