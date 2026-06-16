package com.magi.app.engine

import com.magi.app.model.MagiState

class ResolvedConstraintSet(state: MagiState) {
    val c1: List<C1>
    val c2: List<C2>
    val c3: List<C3>
    val c3n: List<C3>
    val c3m: List<C3>
    val c3mn: List<C3>
    val c41: List<C41>
    val c42: List<C42>

    init {
        c1 = state.cons1.mapNotNull {
            val d1 = it.day1.toIntOrNull() ?: return@mapNotNull null
            val shift = shiftIndex(state, it.shiftKigou)
            val d2 = it.day2.toIntOrNull() ?: return@mapNotNull null
            if (shift >= 0) C1(d1, shift, d2) else null
        }
        c2 = state.cons2.mapNotNull {
            val shift = shiftIndex(state, it.shiftKigou)
            val count = it.count.toIntOrNull() ?: return@mapNotNull null
            if (shift >= 0) C2(shift, count) else null
        }
        c3 = resolveSeq(state, state.cons3.map { it.pattern })
        c3n = resolveSeq(state, state.cons3n.map { it.pattern })
        c3m = resolveSeq(state, state.cons3m.map { it.pattern })
        c3mn = resolveSeq(state, state.cons3mn.map { it.pattern })
        c41 = state.cons41.mapNotNull {
            val group = groupIndex(state, it.groupKigou)
            val shift = shiftIndex(state, it.shiftKigou)
            if (group < 0 || shift < 0) return@mapNotNull null
            C41(group, shift, it.l.toIntOrNull() ?: 0, it.u.toIntOrNull() ?: Int.MAX_VALUE)
        }
        c42 = state.cons42.mapNotNull {
            val g1 = groupIndex(state, it.g1Kigou)
            val g2 = groupIndex(state, it.g2Kigou)
            val s1 = shiftIndex(state, it.s1Kigou)
            val s2 = shiftIndex(state, it.s2Kigou)
            if (g1 >= 0 && g2 >= 0 && s1 >= 0 && s2 >= 0) C42(g1, s1, g2, s2) else null
        }
    }

    private fun resolveSeq(state: MagiState, rows: List<List<String>>): List<C3> = rows.mapNotNull { row ->
        val body = row.takeWhile { it.isNotBlank() }
        if (body.isEmpty()) return@mapNotNull null
        val out = IntArray(body.size)
        for (i in body.indices) {
            val shift = shiftIndex(state, body[i])
            if (shift < 0) return@mapNotNull null
            out[i] = shift
        }
        C3(out)
    }

    private fun shiftIndex(state: MagiState, kigou: String): Int = state.shifts.indexOfFirst { it.kigou == kigou }
    private fun groupIndex(state: MagiState, kigou: String): Int = state.groups.indexOfFirst { it.kigou == kigou }
}
