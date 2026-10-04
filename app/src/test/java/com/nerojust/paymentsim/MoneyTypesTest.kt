package com.nerojust.paymentsim

import com.nerojust.paymentsim.model.formatMinor
import com.nerojust.paymentsim.model.parseAmountMinor
import com.nerojust.paymentsim.server.db.LedgerEntryEntity
import com.nerojust.paymentsim.ui.duplicateChargeIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MoneyTypesTest {

    /** Lint-style: no money-named declaration in main source may be a Double or Float. */
    @Test
    fun mainSources_moneyFields_neverUseFloatingPoint() {
        val moneyAsFloat = Regex(
            """\b\w*(amount|minor|balance|price|fee|limit|total)\w*\s*:\s*(Double|Float)\b""",
            RegexOption.IGNORE_CASE,
        )
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("no sources found, is the working directory the app module?", sources.isNotEmpty())

        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (moneyAsFloat.containsMatchIn(line)) "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }
        assertEquals("Money must be Long minor units", emptyList<String>(), offenders)
    }

    @Test
    fun parseAmountMinor_wholeAndDecimalNaira_returnsKobo() {
        assertEquals(125_000L, parseAmountMinor("1250"))
        assertEquals(125_050L, parseAmountMinor("1250.5"))
        assertEquals(125_099L, parseAmountMinor(" 1250.99 "))
        assertEquals(10L, parseAmountMinor("0.10"))
    }

    @Test
    fun parseAmountMinor_invalidInput_returnsNull() {
        listOf("", "abc", "0", "-5", "1.234", "1e400").forEach { assertNull(it, parseAmountMinor(it)) }
    }

    @Test
    fun formatMinor_kobo_formatsForDisplay() {
        assertEquals("₦1,250.00", formatMinor(125_000))
        assertEquals("₦50,000.01", formatMinor(5_000_001))
        assertEquals("₦0.05", formatMinor(5))
    }

    @Test
    fun duplicateChargeIds_sameAmountAndRecipientWithin60s_flagsBoth() {
        val ledger = listOf(
            charge(1, 125_000, "Ada", createdAt = 0),
            charge(2, 125_000, "Ada", createdAt = 30_000),
            charge(3, 125_000, "Grace", createdAt = 31_000),  // other recipient
            charge(4, 999, "Ada", createdAt = 32_000),         // other amount
            charge(5, 125_000, "Ada", createdAt = 200_000),    // same intent, but far later
        )
        assertEquals(setOf(1L, 2L), duplicateChargeIds(ledger))
    }

    private fun charge(id: Long, amountMinor: Long, recipient: String, createdAt: Long) =
        LedgerEntryEntity(id, idempotencyKey = null, amountMinor = amountMinor, recipient = recipient, createdAt = createdAt)
}
