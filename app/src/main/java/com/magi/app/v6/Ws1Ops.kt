package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import java.time.LocalDate

/**
 * ws1 (初期設定) model operations: edit the problem definition — shifts, groups, staff,
 * the period (days), group×shift buckets, and the use2 flag. Operations that change a
 * dimension (S/T/K) re-dimension every index-keyed structure consistently so the result
 * stays scorable. Re-dimensioning semantics validated against the Level Zero data model
 * (see docs) and a numeric prototype (state stays consistent; fullEval remains computable).
 *
 * Each op takes the current (state, working schedule) and returns a new pair; [newSchedule]
 * always matches the returned state's dimensions. Remove operations (which require shift/
 * staff re-indexing) are intentionally deferred to a later increment.
 */
data class Ws1Result(val state: MagiState, val schedule: Array<IntArray>)

object Ws1Ops {

    private fun withSchedule(state: MagiState, sched: Array<IntArray>): MagiState =
        state.copy(schedule = sched.map { it.toList() })

    private fun copyGrid(sched: Array<IntArray>): Array<IntArray> = Array(sched.size) { sched[it].copyOf() }

    // ---- no dimension change -------------------------------------------------

    fun editShift(state: MagiState, k: Int, name: String, kigou: String, need1: String, need2: String): MagiState {
        if (k !in state.shifts.indices) return state
        val s = state.shifts.toMutableList()
        s[k] = Shift(name, kigou, need1, need2)
        return state.copy(shifts = s)
    }

    fun editGroup(state: MagiState, g: Int, name: String, kigou: String): MagiState {
        if (g !in state.groups.indices) return state
        val gl = state.groups.toMutableList()
        gl[g] = Group(name, kigou)
        return state.copy(groups = gl)
    }

    fun editStaff(state: MagiState, i: Int, name: String, groupIdx: Int): MagiState {
        if (i !in state.staff.indices) return state
        val gi = groupIdx.coerceIn(0, (state.groups.size - 1).coerceAtLeast(0))
        val sl = state.staff.toMutableList()
        sl[i] = Staff(name, gi)
        return state.copy(staff = sl)
    }

    fun setGroupShift(state: MagiState, g: Int, k: Int, allowed: Boolean): MagiState {
        if (g !in state.groupShift.indices) return state
        val grid = state.groupShift.map { it.toMutableList() }.toMutableList()
        if (k !in grid[g].indices) return state
        grid[g][k] = if (allowed) 1 else 0
        return state.copy(groupShift = grid)
    }

    fun setUse2(state: MagiState, on: Boolean): MagiState = state.copy(use2Patterns = on)

    // ---- append (low-risk dimension change, no re-indexing) ------------------

    /** Add a shift (index K). Existing schedule/wishes indices stay valid; groupShift/apt gain a column. */
    fun addShift(state: MagiState, name: String, kigou: String, need1: String, need2: String): MagiState {
        val shifts = state.shifts + Shift(name, kigou, need1, need2)
        val gs = state.groupShift.map { it + 0 }                 // new shift not allowed by default
        val apt = if (state.groupShiftApt.isEmpty()) state.groupShiftApt
        else state.groupShiftApt.map { it + "" }
        return state.copy(shifts = shifts, groupShift = gs, groupShiftApt = apt)
    }

    /** Add a group (index G). groupShift/apt gain a row; staff group indices stay valid.
     *  [review #5] The new group is allowed the 休 (rest) shift so it passes validation
     *  (every group needs >=1 doable shift); otherwise add -> save -> reload would be rejected. */
    fun addGroup(state: MagiState, name: String, kigou: String): MagiState {
        val k = state.shifts.size
        val rest = state.shifts.indexOfFirst { it.kigou == "休" }.let { if (it >= 0) it else 0 }
        val groups = state.groups + Group(name, kigou)
        val gs = state.groupShift + listOf(List(k) { idx -> if (idx == rest) 1 else 0 })
        val apt = if (state.groupShiftApt.isEmpty()) state.groupShiftApt
        else state.groupShiftApt + listOf(List(k) { "" })
        return state.copy(groups = groups, groupShift = gs, groupShiftApt = apt)
    }

    /** Add a staff (index S). The working schedule gains a row of 休/idx0. */
    fun addStaff(state: MagiState, sched: Array<IntArray>, name: String, groupIdx: Int): Ws1Result {
        val t = if (sched.isNotEmpty()) sched[0].size else state.dayCount
        val gi = groupIdx.coerceIn(0, (state.groups.size - 1).coerceAtLeast(0))
        val staff = state.staff + Staff(name, gi)
        val newSched = copyGrid(sched) + IntArray(t) { 0 }
        val ns = state.copy(staff = staff)
        return Ws1Result(withSchedule(ns, newSched), newSched)
    }

    // ---- period resize -------------------------------------------------------

    /** Resize the period to [newT] days: schedule columns padded with 休/idx0 or truncated;
     *  out-of-range needDay/wishes dropped; endDate recomputed from startDate. */
    fun resizeDays(state: MagiState, sched: Array<IntArray>, newT: Int): Ws1Result {
        val t = newT.coerceIn(1, 31)
        val newSched = Array(sched.size) { i ->
            IntArray(t) { j -> if (j < sched[i].size) sched[i][j] else 0 }
        }
        fun dayOf(key: String) = key.split(",").getOrNull(1)?.toIntOrNull() ?: -1
        val need1 = state.needDay1.filterKeys { dayOf(it) in 0 until t }
        val need2 = state.needDay2.filterKeys { dayOf(it) in 0 until t }
        val wishes = state.wishes.filterKeys { dayOf(it) in 0 until t }
        val end = runCatching { LocalDate.parse(state.startDate).plusDays((t - 1).toLong()).toString() }
            .getOrDefault(state.endDate)
        val ns = state.copy(needDay1 = need1, needDay2 = need2, wishes = wishes, endDate = end)
        return Ws1Result(withSchedule(ns, newSched), newSched)
    }

    // ---- remove (re-indexing; verified against a numeric prototype) ----------

    /** Remap a "a,b"-keyed map after removing index [removed] from axis 0 (a) or axis 1 (b):
     *  drop keys whose axis index == removed, decrement those greater. */
    private fun <V> reindexKeys(m: Map<String, V>, axis: Int, removed: Int): Map<String, V> {
        val out = LinkedHashMap<String, V>()
        for ((key, v) in m) {
            val parts = key.split(",")
            val a = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val b = parts.getOrNull(1)?.toIntOrNull() ?: continue
            var idx = if (axis == 0) a else b
            if (idx == removed) continue
            if (idx > removed) idx -= 1
            out[if (axis == 0) "$idx,$b" else "$a,$idx"] = v
        }
        return out
    }

    fun canRemoveGroup(state: MagiState, g: Int): Boolean =
        state.groups.size > 1 && state.staff.none { it.groupIdx == g }

    /** Remove shift [k]: drop the shift; schedule cells ==k -> 休/idx0, >k decremented; wish
     *  values ==k dropped, >k decremented; groupShift/apt lose column k; needDay (axis k) and
     *  staffRange (axis k) re-indexed. Constraints referencing the removed kigou simply stop
     *  resolving (kept verbatim). No-op if only one shift remains. */
    fun removeShift(state: MagiState, sched: Array<IntArray>, k: Int): Ws1Result {
        if (k !in state.shifts.indices || state.shifts.size <= 1) return Ws1Result(state, sched)
        val shifts = state.shifts.filterIndexed { i, _ -> i != k }
        val gs = state.groupShift.map { row -> row.filterIndexed { i, _ -> i != k } }
        val apt = if (state.groupShiftApt.isEmpty()) state.groupShiftApt
        else state.groupShiftApt.map { row -> row.filterIndexed { i, _ -> i != k } }
        val newSched = Array(sched.size) { r ->
            IntArray(sched[r].size) { j -> val v = sched[r][j]; if (v == k) 0 else if (v > k) v - 1 else v }
        }
        val wishes = LinkedHashMap<String, Int>()
        for ((key, v) in state.wishes) { if (v == k) continue; wishes[key] = if (v > k) v - 1 else v }
        val ns = state.copy(
            shifts = shifts, groupShift = gs, groupShiftApt = apt, wishes = wishes,
            needDay1 = reindexKeys(state.needDay1, 0, k),
            needDay2 = reindexKeys(state.needDay2, 0, k),
            staffRange = reindexKeys(state.staffRange, 1, k),
        )
        return Ws1Result(withSchedule(ns, newSched), newSched)
    }

    /** Remove staff [i]: drop the staff and its schedule row; wishes/staffRange (axis i)
     *  re-indexed. No-op if only one staff remains. */
    fun removeStaff(state: MagiState, sched: Array<IntArray>, i: Int): Ws1Result {
        if (i !in state.staff.indices || state.staff.size <= 1) return Ws1Result(state, sched)
        val staff = state.staff.filterIndexed { idx, _ -> idx != i }
        val newSched = ArrayList<IntArray>(sched.size - 1)
        for (r in sched.indices) if (r != i) newSched.add(sched[r].copyOf())
        val arr = newSched.toTypedArray()
        val ns = state.copy(
            staff = staff,
            wishes = reindexKeys(state.wishes, 0, i),
            staffRange = reindexKeys(state.staffRange, 0, i),
        )
        return Ws1Result(withSchedule(ns, arr), arr)
    }

    /** Remove group [g]: only when empty (no staff) and not the last group. groupShift/apt lose
     *  the row; staff group indices > g are decremented. Constraints referencing the removed
     *  group kigou simply stop resolving. */
    fun removeGroup(state: MagiState, g: Int): MagiState {
        if (g !in state.groups.indices || !canRemoveGroup(state, g)) return state
        val groups = state.groups.filterIndexed { idx, _ -> idx != g }
        val gs = state.groupShift.filterIndexed { idx, _ -> idx != g }
        val apt = if (state.groupShiftApt.isEmpty()) state.groupShiftApt
        else state.groupShiftApt.filterIndexed { idx, _ -> idx != g }
        val staff = state.staff.map { if (it.groupIdx > g) Staff(it.name, it.groupIdx - 1) else it }
        return state.copy(groups = groups, groupShift = gs, groupShiftApt = apt, staff = staff)
    }
}
