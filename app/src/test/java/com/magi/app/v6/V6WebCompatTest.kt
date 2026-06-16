package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6WebCompatTest {
    @Test fun basicUtilitiesWork() {
        assertEquals("A", V6WebCompat.colLetter(0))
        assertEquals("Z", V6WebCompat.colLetter(25))
        assertEquals("AA", V6WebCompat.colLetter(26))
        assertEquals("rest", V6WebCompat.shiftCatDefault("休"))
        assertEquals("CRITICAL", V6WebCompat.severityFromVioKey("groupViol"))
        assertEquals(3, V6WebCompat.popcnt32(0b1011))
    }

    @Test fun historyReducerPushUndoRedo() {
        val st = sampleState()
        val report = UnifiedViolationChecker.check(st)
        val entry = V6WebCompat.HistoryEntry("init", st.schedule.toIntArray2D(), report)
        val h1 = V6WebCompat.historyReducer(V6WebCompat.makeInitialHistoryState(), V6WebCompat.HistoryAction.Push(entry))
        assertEquals(1, h1.undo.size)
        val h2 = V6WebCompat.historyReducer(h1, V6WebCompat.HistoryAction.Undo)
        assertEquals(0, h2.undo.size)
        assertEquals(1, h2.redo.size)
        val h3 = V6WebCompat.historyReducer(h2, V6WebCompat.HistoryAction.Redo)
        assertEquals(1, h3.undo.size)
    }

    @Test fun workbookAndDiagnosticsWork() {
        val st = sampleState()
        val wb = V6WebCompat.buildWorkbook(st)
        assertEquals(7, wb.sheets.size)
        assertTrue(wb.sheets.first().toTsv().contains("Staff"))
        assertTrue(V6WebCompat.buildImpossibleAssignmentSummary(st).isEmpty())
        assertTrue(V6WebCompat.buildDistributionReview(st).summary.contains("負荷範囲"))
    }

    private fun sampleState(): MagiState = MagiState(
        startDate = "2026-06-01",
        endDate = "2026-06-02",
        shifts = listOf(Shift("日勤", "日", "1", "1"), Shift("休み", "休", "", "")),
        groups = listOf(Group("A", "A")),
        staff = listOf(Staff("s1", 0), Staff("s2", 0)),
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = listOf(listOf(0, 1), listOf(1, 0)),
        wishes = emptyMap(),
        staffRange = mapOf("0,0" to Range("0", "2")),
        needDay1 = emptyMap(),
        needDay2 = emptyMap(),
        cons1 = emptyList(),
        cons2 = emptyList(),
        cons3 = emptyList(),
        cons3n = emptyList(),
        cons3m = emptyList(),
        cons3mn = emptyList(),
        cons41 = emptyList(),
        cons42 = emptyList(),
    )
}
