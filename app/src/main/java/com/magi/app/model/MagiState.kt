package com.magi.app.model

/**
 * MAGI shift-scheduling problem state.
 *
 * Field names and semantics mirror the Web app's `state` object exactly, so JSON
 * exported from the Web version (and vice-versa) round-trips without conversion.
 *
 *  - shifts[k]      : a shift type. need1/need2 = default per-day required count
 *                     for pattern P1/P2 ("" / null = no requirement).
 *  - groups[g]      : a staff group (skill class). kigou = symbol used in constraints.
 *  - staff[i]       : a person; groupIdx points into groups.
 *  - groupShift[g]  : per-group 0/1 mask of which shifts the group may take.
 *  - groupShiftApt[g][k] : V6 “適切回数” target for group×shift (blank = unset).
 *  - use2Patterns   : whether the P2 coverage generation is active (MIN=OR with P1).
 *  - schedule[i][j] : initial assignment = shift index for staff i on day j.
 *  - wishes["i,j"]  : desired shift index for a cell (hard-ish preference).
 *  - staffRange["i,k"] = {lo,hi} : per-staff per-shift count range (LimMin/LimMax).
 *  - needDay1/needDay2["k,j"]    : per-day need override for shift k on day j.
 *  - cons1..cons42  : the constraint families (see resolveConstraints / Evaluator).
 */
data class Shift(val name: String, val kigou: String, val need1: String, val need2: String)
data class Group(val name: String, val kigou: String)
data class Staff(val name: String, val groupIdx: Int)
data class Range(val lo: String, val hi: String)

/** Raw constraint rows (as authored), resolved later into index form. */
data class C1Row(val day1: String, val shiftKigou: String, val day2: String)
data class C2Row(val shiftKigou: String, val count: String)
data class C3Row(val pattern: List<String>)
data class C41Row(val groupKigou: String, val shiftKigou: String, val l: String, val u: String)
data class C42Row(val g1Kigou: String, val g2Kigou: String, val s1Kigou: String, val s2Kigou: String)

data class MagiState(
    val startDate: String,
    val endDate: String,
    val shifts: List<Shift>,
    val groups: List<Group>,
    val staff: List<Staff>,
    val use2Patterns: Boolean,
    val groupShift: List<List<Int>>,
    val groupShiftApt: List<List<String>>,
    val schedule: List<List<Int>>,
    val wishes: Map<String, Int>,
    val staffRange: Map<String, Range>,
    val needDay1: Map<String, String>,
    val needDay2: Map<String, String>,
    val cons1: List<C1Row>,
    val cons2: List<C2Row>,
    val cons3: List<C3Row>,
    val cons3n: List<C3Row>,
    val cons3m: List<C3Row>,
    val cons3mn: List<C3Row>,
    val cons41: List<C41Row>,
    val cons42: List<C42Row>,
    /** Per-shift display colour overrides, keyed by shift kigou -> "#rrggbb". Display only (no engine effect). */
    val shiftColors: Map<String, String> = emptyMap(),
    /** Anything we do not model yet, kept verbatim so export round-trips losslessly. */
    val extras: Map<String, Any?> = emptyMap(),
) {
    val staffCount: Int get() = staff.size
    val dayCount: Int get() = if (schedule.isNotEmpty()) schedule[0].size else 0
    val shiftCount: Int get() = shifts.size
    val groupCount: Int get() = groups.size
}
