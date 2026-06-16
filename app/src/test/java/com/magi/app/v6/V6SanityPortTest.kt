package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6SanityPortTest {
    @Test fun detectsImpossibleWishAndInvalidAssignment() {
        val st = MagiState(
            startDate = "2026-06-01",
            endDate = "2026-06-02",
            shifts = listOf(Shift("休", "休", "", ""), Shift("A", "A", "1", "")),
            groups = listOf(Group("G", "G")),
            staff = listOf(Staff("s0", 0)),
            use2Patterns = false,
            groupShift = listOf(listOf(1, 0)),
            groupShiftApt = listOf(listOf("", "")),
            schedule = listOf(listOf(1, 0)),
            wishes = mapOf("0,0" to 1),
            staffRange = emptyMap(),
            needDay1 = emptyMap(),
            needDay2 = emptyMap(),
            cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
            cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        )
        val rep = V6SanityPort.build(st)
        assertEquals(1, rep.impossibleWishes.size)
        assertTrue(rep.warns.any { it.contains("実現不能") })
        assertTrue(rep.warns.any { it.contains("担当不可") })
    }
}
