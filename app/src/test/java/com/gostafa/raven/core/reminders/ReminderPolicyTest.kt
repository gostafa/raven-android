package com.gostafa.raven.core.reminders

import com.gostafa.raven.core.database.ReminderOccurrence
import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    private val due = listOf(
        ReminderOccurrence("a1", "a", 100, "Words"),
        ReminderOccurrence("a2", "a", 100, "Words"),
        ReminderOccurrence("b1", "b", 100, "Words"),
    )
    @Test fun simultaneousPartsAreGroupedByCard() {
        val plan = planReminderDelivery(due, null, true, true)
        assertEquals(setOf("a", "b"), plan.batches.keys)
        assertEquals(listOf("a1", "a2"), plan.batches.getValue("a").map { it.partId })
        assertTrue(plan.suppressed.isEmpty())
    }
    @Test fun onlyActiveCardIsSuppressedAndAcknowledged() {
        val plan = planReminderDelivery(due, "a", true, true)
        assertEquals(setOf("b"), plan.batches.keys)
        assertEquals(listOf("a1", "a2"), plan.suppressed.map { it.partId })
    }
    @Test fun blockedPermissionLeavesOtherOccurrencesPending() {
        val plan = planReminderDelivery(due, "a", true, false)
        assertTrue(plan.batches.isEmpty())
        assertEquals(2, plan.suppressed.size)
        assertEquals(2, planReminderDelivery(due, null, true, true).batches.size)
    }
    @Test fun disabledRemindersConsumeNothing() {
        val plan = planReminderDelivery(due, "a", false, true)
        assertTrue(plan.batches.isEmpty())
        assertTrue(plan.suppressed.isEmpty())
    }
}
