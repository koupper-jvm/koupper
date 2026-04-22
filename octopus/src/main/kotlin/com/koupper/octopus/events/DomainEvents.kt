package com.koupper.octopus.events

interface DomainEvent

interface EventBus {
    fun publish(event: DomainEvent)
}

interface DomainEventListener<E : DomainEvent> {
    fun on(event: E)
}
