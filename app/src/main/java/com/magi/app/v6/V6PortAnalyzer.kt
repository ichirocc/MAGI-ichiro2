package com.magi.app.v6

import com.magi.app.model.C3Row
import com.magi.app.model.MagiState
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Native port of the V6 Web overview / risk / load analysis layer. */
data class V6DayRisk(
    val dayIndex: Int,
    val label: String,
    val shortage: Int,
    val detail: String,
)

data class V6StaffProfile(
    val staffIndex: Int,
    val name: String,
    val groupSymbol: String,
    val workCount: Int,
    val violationCount: Int,
    val workloadText: String,
)

data class V6PortReport(
    val coveragePct: Int?,
    val demand: Int,
    val covU: Int,
    val hardCore: Int,
    val hardGuard: Int,
    val softCore: Int,
    val topRiskDay: Int,
    val topRiskLabel: String,
    val topRiskShortage: Int,
    val dayRisks: List<V6DayRisk>,
    val staffProfiles: List<V6StaffProfile>,
    val aptPenalty: Double,
    val equPenalty: Double,
    val sanityWarnings: List<String>,
    val sanityNotes: List<String>,
) {
    /** Days that still have a coverage shortage (referenced by the V6 RISK gauge). */
    val highRiskDays: Int get() = dayRisks.count { it.shortage > 0 }
}

object V6PortAnalyzer {
    fun analyze(
        state: MagiState,
        schedule: Array<IntArray> = state.schedule.toIntArray2D(),
        report: ViolationReport = UnifiedViolationChecker.check(state, schedule),
    ): V6PortReport {
        val p = Problem(state)
        val normalized = normalizeSchedule(schedule, p)
        val cov = coverage(p, normalized)
        val counts = countMatrix(p, normalized)
        val dayRisks = buildDayRisks(state, p, cov)
        val demand = totalDemand(p)
        val covU = report.breakdown["covU"] ?: 0
        val coveragePct = if (demand > 0) {
            (((demand - covU).coerceAtLeast(0).toDouble() / demand.toDouble()) * 100.0).roundToInt()
        } else null

        var topRiskDay = -1
        var topRiskLabel = "-"
        var topRiskShortage = 0
        for (risk in dayRisks) {
            if (risk.shortage > topRiskShortage) {
                topRiskDay = risk.dayIndex
                topRiskLabel = risk.label
                topRiskShortage = risk.shortage
            }
        }

        val hardGuard = report.breakdown["groupViol"] ?: 0
        val hardCore = (report.breakdown["c3n"] ?: 0) + (report.breakdown["covU"] ?: 0) + (report.breakdown["pref"] ?: 0)
        val softCore = (report.total - hardGuard - hardCore).coerceAtLeast(0)
        val staffViol = staffViolationCounts(p, report)

        return V6PortReport(
            coveragePct = coveragePct,
            demand = demand,
            covU = covU,
            hardCore = hardCore,
            hardGuard = hardGuard,
            softCore = softCore,
            topRiskDay = topRiskDay,
            topRiskLabel = topRiskLabel,
            topRiskShortage = topRiskShortage,
            dayRisks = dayRisks,
            staffProfiles = buildStaffProfiles(state, p, normalized, counts, staffViol),
            aptPenalty = aptPenalty(state, p, counts),
            equPenalty = equalizationPenalty(state, p, normalized, counts, report.breakdown),
            sanityWarnings = sanityWarnings(state, p, normalized),
            sanityNotes = sanityNotes(state),
        )
    }

    private fun totalDemand(p: Problem): Int {
        var out = 0
        for (j in 0 until p.T) {
            for (k in 0 until p.K) {
                val need = p.need1[k][j]
                if (need > 0) out += need
            }
        }
        return out
    }

    private fun buildDayRisks(state: MagiState, p: Problem, cov: Array<IntArray>): List<V6DayRisk> {
        val out = ArrayList<V6DayRisk>(p.T)
        for (j in 0 until p.T) {
            var shortfall = 0
            val parts = ArrayList<String>()
            for (k in 0 until p.K) {
                val need = p.need1[k][j]
                if (need < 0) continue
                val miss = (need - cov[j][k]).coerceAtLeast(0)
                shortfall += miss
                if (miss > 0) {
                    val sym = state.shifts.getOrNull(k)?.kigou ?: k.toString()
                    parts.add("${sym}×${miss}")
                }
            }
            out.add(V6DayRisk(j, dayLabel(state.startDate, j), shortfall, parts.joinToString(" ")))
        }
        return out
    }

    private fun staffViolationCounts(p: Problem, report: ViolationReport): IntArray {
        val out = IntArray(p.S)
        fun addCellKey(key: String) {
            val i = key.substringBefore(',').toIntOrNull() ?: return
            if (i in 0 until p.S) out[i]++
        }
        for (key in report.violations.keys) addCellKey(key)
        for (key in report.countViolations.keys) addCellKey(key)
        return out
    }

    private fun buildStaffProfiles(
        state: MagiState,
        p: Problem,
        schedule: Array<IntArray>,
        counts: Array<IntArray>,
        staffViol: IntArray,
    ): List<V6StaffProfile> {
        val rest = state.shifts.indexOfFirst { shift -> shift.kigou == "休" }
        val profiles = ArrayList<V6StaffProfile>(p.S)
        for (i in 0 until p.S) {
            var work = 0
            for (j in 0 until p.T) if (schedule[i][j] != rest) work++

            val pairs = ArrayList<Pair<Int, Int>>()
            for (k in 0 until p.K) {
                val n = counts[i][k]
                if (n > 0) pairs.add(Pair(k, n))
            }
            pairs.sortByDescending { pair -> pair.second }
            val parts = ArrayList<String>()
            val limit = minOf(3, pairs.size)
            for (idx in 0 until limit) {
                val k = pairs[idx].first
                val n = pairs[idx].second
                val sym = state.shifts.getOrNull(k)?.kigou ?: k.toString()
                parts.add("${sym}:${n}")
            }
            val staff = state.staff.getOrNull(i)
            val g = staff?.groupIdx ?: -1
            profiles.add(
                V6StaffProfile(
                    staffIndex = i,
                    name = staff?.name ?: "#${i}",
                    groupSymbol = state.groups.getOrNull(g)?.kigou ?: "",
                    workCount = work,
                    violationCount = staffViol.getOrElse(i) { 0 },
                    workloadText = parts.joinToString(" / "),
                )
            )
        }
        profiles.sortWith(compareByDescending<V6StaffProfile> { it.violationCount }.thenByDescending { it.workCount })
        return profiles
    }

    /** V6 CountApt port: sum((count - apt)^2 / apt^2), group aptitude expanded to staff. */
    private fun aptPenalty(state: MagiState, p: Problem, counts: Array<IntArray>): Double {
        var out = 0.0
        for (i in 0 until p.S) {
            val g = p.sgrp.getOrNull(i) ?: continue
            val row = state.groupShiftApt.getOrNull(g) ?: continue
            for (k in 0 until p.K) {
                val apt = row.getOrNull(k)?.trim()?.toDoubleOrNull() ?: continue
                if (apt <= 0.0) continue
                val d = counts[i][k].toDouble() - apt
                out += (d * d) / (apt * apt)
            }
        }
        return out
    }

    /** Compact V6 equalization overview: member variance + day-of-week variance with HARD-aware psi. */
    private fun equalizationPenalty(
        state: MagiState,
        p: Problem,
        schedule: Array<IntArray>,
        counts: Array<IntArray>,
        breakdown: Map<String, Int>,
    ): Double {
        if (p.S == 0 || p.T == 0 || p.K == 0 || p.G == 0) return 0.0
        val members = Array(p.G) { ArrayList<Int>() }
        for (i in 0 until p.S) {
            val g = p.sgrp[i]
            if (g in 0 until p.G) members[g].add(i)
        }

        var raw = 0.0
        for (g in 0 until p.G) {
            val gs = state.groupShift.getOrNull(g) ?: continue
            val mem = members[g]
            if (mem.isEmpty()) continue
            for (k in 0 until p.K) {
                if (gs.getOrNull(k) != 1) continue
                if (mem.size == 1) {
                    val i = mem[0]
                    val explicit = explicitTarget(p, i, k)
                    val apt = state.groupShiftApt.getOrNull(g)?.getOrNull(k)?.trim()?.toDoubleOrNull()
                    val target = explicit ?: apt ?: continue
                    val dev = counts[i][k].toDouble() - target
                    raw += dev * dev + abs(dev) * 2.0
                } else {
                    var sum = 0
                    for (i in mem) sum += counts[i][k]
                    val mean = sum.toDouble() / mem.size.toDouble()
                    var varSum = 0.0
                    var maxDev = 0.0
                    for (i in mem) {
                        val d = counts[i][k].toDouble() - mean
                        varSum += d * d
                        maxDev = max(maxDev, abs(d))
                    }
                    raw += varSum + maxDev * 2.0
                }
            }
        }

        val startDow = startDow(state.startDate)
        val dowCnt = Array(p.S) { Array(7) { IntArray(p.K) } }
        for (i in 0 until p.S) {
            for (j in 0 until p.T) {
                val k = schedule[i][j]
                if (k in 0 until p.K) dowCnt[i][(startDow + j) % 7][k]++
            }
        }
        for (g in 0 until p.G) {
            val gs = state.groupShift.getOrNull(g) ?: continue
            val mem = members[g]
            if (mem.size <= 1) continue
            for (dow in 0 until 7) {
                for (k in 0 until p.K) {
                    if (gs.getOrNull(k) != 1) continue
                    var sum = 0
                    for (i in mem) sum += dowCnt[i][dow][k]
                    val mean = sum.toDouble() / mem.size.toDouble()
                    var varSum = 0.0
                    var maxDev = 0.0
                    for (i in mem) {
                        val d = dowCnt[i][dow][k].toDouble() - mean
                        varSum += d * d
                        maxDev = max(maxDev, abs(d))
                    }
                    raw += varSum + maxDev * 2.0
                }
            }
        }
        val hard = (breakdown["groupViol"] ?: 0) + (breakdown["c3n"] ?: 0) + (breakdown["covU"] ?: 0) + (breakdown["pref"] ?: 0)
        val psi = max(0.2, 1.0 / (1.0 + 10.0 * hard.toDouble()))
        return raw * psi
    }

    private fun explicitTarget(p: Problem, i: Int, k: Int): Double? {
        val loSet = p.rangeLo[i][k] != Int.MIN_VALUE
        val hiSet = p.rangeHi[i][k] != Int.MAX_VALUE
        return when {
            loSet && hiSet -> (p.rangeLo[i][k] + p.rangeHi[i][k]).toDouble() / 2.0
            loSet -> p.rangeLo[i][k].toDouble()
            hiSet -> p.rangeHi[i][k].toDouble()
            else -> null
        }
    }

    private fun sanityWarnings(state: MagiState, p: Problem, schedule: Array<IntArray>): List<String> {
        val warns = ArrayList<String>()
        var badAssign = 0
        for (i in 0 until p.S) {
            for (j in 0 until p.T) {
                val k = schedule[i][j]
                if (k in 0 until p.K && !p.canDo(i, k)) badAssign++
            }
        }
        if (badAssign > 0) warns.add("担当不可の配置が ${badAssign} セルあります")

        var badWish = 0
        for ((key, k) in state.wishes) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val j = parts.getOrNull(1)?.toIntOrNull()
            if (i == null || j == null || i !in 0 until p.S || j !in 0 until p.T || k !in 0 until p.K) {
                badWish++
            } else if (!p.canDo(i, k)) {
                badWish++
            }
        }
        if (badWish > 0) warns.add("範囲外または担当外の希望シフトが ${badWish} 件あります")

        var badRange = 0
        for ((key, r) in state.staffRange) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull()
            val k = parts.getOrNull(1)?.toIntOrNull()
            val lo = r.lo.trim().toIntOrNull()
            val hi = r.hi.trim().toIntOrNull()
            if (i == null || k == null || i !in 0 until p.S || k !in 0 until p.K) badRange++
            if (lo != null && hi != null && lo > hi) badRange++
        }
        if (badRange > 0) warns.add("staffRange の範囲外キーまたは lo>hi が ${badRange} 件あります")

        val dup = duplicatePatternCount(state)
        if (dup > 0) warns.add("連続パターン制約の重複定義が ${dup} 件あります")
        if (state.cons41.isEmpty()) warns.add("cons41 が未設定です（グループ別人数範囲を使う場合は確認）")
        return warns
    }

    private fun sanityNotes(state: MagiState): List<String> {
        val notes = ArrayList<String>()
        var aptSet = 0
        for (row in state.groupShiftApt) for (cell in row) if (cell.trim().isNotEmpty()) aptSet++
        notes.add("groupShiftApt 適切回数: ${aptSet} 件設定")
        if (state.needDay1.isEmpty() && state.needDay2.isEmpty()) notes.add("needDay1/needDay2 は全空です（shift既定 need を使用）")
        notes.add("V6 Native overview: リスクカレンダー / 負荷プロフィール / SanityCheck 有効")
        return notes
    }

    private fun duplicatePatternCount(state: MagiState): Int {
        return countDuplicatePatternRows(state.cons3) +
            countDuplicatePatternRows(state.cons3n) +
            countDuplicatePatternRows(state.cons3m) +
            countDuplicatePatternRows(state.cons3mn)
    }

    private fun countDuplicatePatternRows(rows: List<C3Row>): Int {
        val seen = HashSet<String>()
        var dup = 0
        for (row in rows) {
            val symbols = ArrayList<String>()
            for (s in row.pattern) {
                if (s.isBlank()) break
                symbols.add(s)
            }
            val key = symbols.joinToString("→")
            if (key.isBlank()) continue
            if (!seen.add(key)) dup++
        }
        return dup
    }
}

fun dayLabel(startDate: String, offset: Int): String {
    return try {
        val d = LocalDate.parse(startDate).plusDays(offset.toLong())
        val wd = "月火水木金土日"[d.dayOfWeek.value - 1]
        "${d.monthValue}/${d.dayOfMonth}(${wd})"
    } catch (_: Exception) {
        "${offset + 1}日"
    }
}

private fun startDow(startDate: String): Int {
    return try {
        LocalDate.parse(startDate).dayOfWeek.value % 7
    } catch (_: Exception) {
        0
    }
}
