package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Range
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6FinalBridgePortTest {
    @Test fun algorithmLabelsMatchWebThresholds() {
        assertEquals("v5", V6FinalPort.getAlgorithmLabel(10).tech)
        assertEquals("v5", V6FinalPort.getAlgorithmLabel(30).tech)
        assertEquals("ALNS×1", V6FinalPort.getAlgorithmLabel(60).tech)
        assertEquals("ALNS×2", V6FinalPort.getAlgorithmLabel(180).tech)
        assertEquals("RSI→ALNS", V6FinalPort.getAlgorithmLabel(300).tech)
        assertEquals("RSI++", V6FinalPort.getAlgorithmLabel(600).tech)
        assertEquals("RSI++拡張", V6FinalPort.getAlgorithmLabel(1200).tech)
    }

    @Test fun busyDetailAndGateWork() {
        val st = sampleState()
        val b = V6FinalPort.buildBusyDetail(st, "違反チェック中")
        assertTrue(b.problemSize.contains("2名"))
        assertTrue(b.constraintCount.contains("希望 0件"))
        assertTrue(V6FinalPort.confirmDespiteImpossibleWishes(st).allowed)
    }

    @Test fun postHotfixChainReturnsReport() {
        val st = sampleState()
        val post = V6HotfixPasses.runPostOptimization(st, st.schedule.toIntArray2D(), "test")
        assertEquals(0, V6WebCompat.invalidAssignmentCount(st, post.schedule))
        assertTrue(post.logs.isNotEmpty())
        assertEquals(post.report.total, UnifiedViolationChecker.check(st, post.schedule).total)
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
