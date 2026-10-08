package com.nerojust.paymentsim.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class LogSource { CLIENT, NET, SERVER, WORKER }

data class LogEvent(val id: Long, val timeMillis: Long, val source: LogSource, val message: String)

/**
 * With a [file], every line is also written to disk, so the story before "Crash the app" is still there
 * after it. Without one (tests) it is in memory only.
 */
// ponytail: one append per line, and the file is only emptied by clear(). Fine for a demo; rotate it if it must run for days.
class EventLog(private val file: File? = null) {
    private val nextId = AtomicLong()
    private val _events = MutableStateFlow(load())

    /** Newest first. */
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    fun log(source: LogSource, message: String) {
        val event = LogEvent(nextId.incrementAndGet(), System.currentTimeMillis(), source, message)
        _events.update { (listOf(event) + it).take(MAX_EVENTS) }
        // Written before log() returns, so a line logged right before the process is killed survives.
        synchronized(this) {
            runCatching { file?.appendText("${event.timeMillis}\t${source.name}\t${message.replace('\n', ' ')}\n") }
        }
    }

    fun clear() {
        _events.value = emptyList()
        synchronized(this) { file?.delete() }
    }

    private fun load(): List<LogEvent> {
        val lines = runCatching { file?.readLines() }.getOrNull().orEmpty()
        return lines.takeLast(MAX_EVENTS).mapNotNull { line ->
            val (time, source, message) = line.split('\t', limit = 3).takeIf { it.size == 3 } ?: return@mapNotNull null
            val logSource = LogSource.entries.firstOrNull { it.name == source } ?: return@mapNotNull null
            LogEvent(nextId.incrementAndGet(), time.toLongOrNull() ?: return@mapNotNull null, logSource, message)
        }.asReversed()
    }

    private companion object {
        const val MAX_EVENTS = 200
    }
}

fun String.shortKey(): String = take(8)
