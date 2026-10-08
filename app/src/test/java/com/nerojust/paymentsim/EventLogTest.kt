package com.nerojust.paymentsim

import com.nerojust.paymentsim.log.EventLog
import com.nerojust.paymentsim.log.LogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EventLogTest {

    private val file = File.createTempFile("event_log", ".txt").apply { deleteOnExit() }

    @Test
    fun linesSurviveARestart_oldestStaysFirst() {
        EventLog(file).apply {
            log(LogSource.CLIENT, "Saved on this phone")
            log(LogSource.SERVER, "Charged\tonce") // a tab in the message must not break the line
        }

        val afterCrash = EventLog(file)
        afterCrash.log(LogSource.CLIENT, "App restarted")

        // events is newest first
        assertEquals(
            listOf("App restarted", "Charged\tonce", "Saved on this phone"),
            afterCrash.events.value.map { it.message },
        )
        assertEquals(LogSource.SERVER, afterCrash.events.value[1].source)
    }

    @Test
    fun clear_alsoEmptiesTheFile() {
        EventLog(file).apply {
            log(LogSource.CLIENT, "Saved on this phone")
            clear()
        }

        assertTrue(EventLog(file).events.value.isEmpty())
    }
}
