package com.nerojust.paymentsim.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class LogSource { CLIENT, NET, SERVER, WORKER }

data class LogEvent(val id: Long, val timeMillis: Long, val source: LogSource, val message: String)

/** In-memory on purpose: it is lost on "Kill app", which is what makes the restart line visible. */
class EventLog {
    private val nextId = AtomicLong()
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())

    /** Newest first. */
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    fun log(source: LogSource, message: String) {
        val event = LogEvent(nextId.incrementAndGet(), System.currentTimeMillis(), source, message)
        _events.update { (listOf(event) + it).take(MAX_EVENTS) }
    }

    fun clear() {
        _events.value = emptyList()
    }

    private companion object {
        const val MAX_EVENTS = 200
    }
}

fun String.shortKey(): String = take(8)
