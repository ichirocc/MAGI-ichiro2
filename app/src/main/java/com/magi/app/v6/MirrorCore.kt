package com.magi.app.v6

import com.magi.app.model.MagiState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import kotlin.math.exp
import kotlin.math.max

/**
 * Kotlin port of magi_python_mirror.py.
 *
 * The high-speed native SA remains the main optimizer, but this module brings the
 * mirror app's operational layer into the Android app: unified violation breakdown,
 * greedy/simple schedule creation, light local search, and CSV round-trip helpers.
 */
data class MirrorLog(
    val ts: Long = System.currentTimeMillis(),
    val iter: Long = 0,
    val level: String = "I",
    val tag: String,
    val message: String,
)

data class ViolationReport(
    val violations: Map<String, String>,
    val needViolations: Map<String, String>,
    val countViolations: Map<String, String>,
    val breakdown: Map<String, Int>,
    val total: Int,
    val hard: Int,
    val soft: Int,
    val weightedScore: Double,
    val logs: List<MirrorLog> = emptyList(),
)

data class ScheduleRunResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
)

data class LightOptimizeResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val iterations: Long,
    val accepts: Long,
    val elapsedMs: Long,
)

object MirrorKeys {
    val hard = listOf("groupViol", "c3n", "covU", "pref")
    val soft = listOf("c1", "c2", "c3", "c3m", "c3mn", "c41", "c42", "covO", "low", "high")
    val all = listOf("c1", "c2", "c3", "c3n", "c3m", "c3mn", "c41", "c42", "covU", "covO", "pref", "low", "high", "groupViol")
}

object UnifiedViolationChecker {
    private val vioClass = mapOf(
        "c1" to "vio-c1", "c2" to "vio-c2", "c3" to "vio-c3", "c3n" to "vio-c3n",
        "c3m" to "vio-c3m", "c3mn" to "vio-c3mn", "c41" to "vio-c41", "c42" to "vio-c42",
        "covU" to "vio-covU", "covO" to "vio-covO", "pref" to "vio-pref",
        "low" to "vio-low", "high" to "vio-high", "groupViol" to "vio-groupViol",
    )

    fun check(state: MagiState, schedule: Array<IntArray> = state.schedule.toIntArray2D()): ViolationReport {
        val t0 = System.nanoTime()
        val p = Problem.of(state)
        val s = normalizeSchedule(schedule, p)
        val breakdown = linkedMapOf<String, Int>()
        for (key0 in MirrorKeys.all) breakdown[key0] = 0
        val violations = linkedMapOf<String, String>()
        val needViolations = linkedMapOf<String, String>()
        val countViolations = linkedMapOf<String, String>()

        fun inc(key: String, amount: Int = 1) { breakdown[key] = (breakdown[key] ?: 0) + amount }
        fun mark(i: Int, j: Int, family: String) { violations["$i,$j"] = vioClass[family] ?: family }
        fun markNeed(k: Int, j: Int, family: String) { needViolations["$k,$j"] = vioClass[family] ?: family }
        fun markCount(i: Int, k: Int, family: String) { countViolations["$i,$k"] = vioClass[family] ?: family }
        fun cellIs(i: Int, j: Int, k: Int): Boolean = i in 0 until p.S && j in 0 until p.T && s[i][j] == k

        for (c in p.cons1) {
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                var j = 0
                while (j <= p.T - c.day1) {
                    var z = 0
                    for (l in 0 until c.day1) if (cellIs(i, j + l, c.shiftIdx)) z++
                    if (z < c.day2) {
                        inc("c1")
                        for (l in 0 until c.day1) mark(i, j + l, "c1")
                    }
                    j++
                }
            }
        }

        val counts = countMatrix(p, s)
        for (c in p.cons2) {
            for (i in 0 until p.S) {
                if (!p.canDo(i, c.shiftIdx)) continue
                if (counts[i][c.shiftIdx] < c.count) {
                    inc("c2")
                    markCount(i, c.shiftIdx, "c2")
                }
            }
        }

        for (c in p.cons41) {
            for (j in 0 until p.T) {
                var z = 0
                for (i in 0 until p.S) if (p.sgrp[i] == c.groupIdx && cellIs(i, j, c.shiftIdx)) z++
                if (z < c.l || z > c.u) {
                    inc("c41")
                    markNeed(c.shiftIdx, j, "c41")
                }
            }
        }

        for (c in p.cons42) {
            for (j in 0 until p.T) {
                val left = ArrayList<Int>()
                val right = ArrayList<Int>()
                for (i in 0 until p.S) {
                    if (p.sgrp[i] == c.g1 && cellIs(i, j, c.s1)) left.add(i)
                    if (p.sgrp[i] == c.g2 && cellIs(i, j, c.s2)) right.add(i)
                }
                for (i in left) for (i2 in right) {
                    inc("c42")
                    mark(i, j, "c42")
                    mark(i2, j, "c42")
                }
            }
        }

        checkC3Family(p, s, p.cons3, "c3", forbidden = false, { key -> inc(key) }, ::mark)
        checkC3Family(p, s, p.cons3n, "c3n", forbidden = true, { key -> inc(key) }, ::mark)
        checkC3Family(p, s, p.cons3m, "c3m", forbidden = false, { key -> inc(key) }, ::mark)
        checkC3Family(p, s, p.cons3mn, "c3mn", forbidden = true, { key -> inc(key) }, ::mark)

        for (i in 0 until p.S) for (j in 0 until p.T) {
            val w = p.wish[i][j]
            if (w in 0 until p.K && s[i][j] != w) {
                inc("pref")
                mark(i, j, "pref")
            }
        }

        for (i in 0 until p.S) {
            for (k in 0 until p.K) {
                val lo = p.rangeLo[i][k]
                val hi = p.rangeHi[i][k]
                val n = counts[i][k]
                if (lo != Int.MIN_VALUE && lo != 0 && p.canDo(i, k) && n < lo) {
                    inc("low", lo - n)
                    markCount(i, k, "low")
                }
                if (hi != Int.MAX_VALUE && n > hi) {
                    inc("high", n - hi)
                    markCount(i, k, "high")
                }
            }
        }

        val cov = coverage(p, s)
        for (j in 0 until p.T) {
            for (k in 0 until p.K) {
                val lo = p.need1[k][j]
                if (lo < 0) continue
                val hi = if (p.use2 && p.need2[k][j] >= 0) p.need2[k][j] else lo
                val got = cov[j][k]
                if (got < lo) {
                    inc("covU", lo - got)
                    markNeed(k, j, "covU")
                } else if (got > hi) {
                    inc("covO", got - hi)
                    markNeed(k, j, "covO")
                }
            }
        }

        for (i in 0 until p.S) for (j in 0 until p.T) {
            val k = s[i][j]
            if (k in 0 until p.K && !p.canDo(i, k)) {
                inc("groupViol")
                mark(i, j, "groupViol")
            }
        }

        var total = 0
        for (v in breakdown.values) total += v
        var hard = 0
        for (key0 in MirrorKeys.hard) hard += breakdown[key0] ?: 0
        val soft = total - hard
        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L)
        val hardParts = ArrayList<String>()
        for (key0 in MirrorKeys.hard) hardParts.add("${key0}=${breakdown[key0] ?: 0}")
        val hardStr = hardParts.joinToString(" ")
        val softParts = ArrayList<String>()
        for (key0 in MirrorKeys.soft) {
            val n = breakdown[key0] ?: 0
            if (n > 0) softParts.add("${key0}=${n}")
        }
        val softStr = softParts.joinToString(" ")
        val msg = if (total == 0) {
            "違反なし"
        } else {
            "合計=$total | HARD=$hard [$hardStr]" + if (soft > 0) " | SOFT=$soft [$softStr]" else ""
        }
        val level = if (total == 0) "I" else "W"
        return ViolationReport(
            violations = violations,
            needViolations = needViolations,
            countViolations = countViolations,
            breakdown = breakdown,
            total = total,
            hard = hard,
            soft = soft,
            weightedScore = weightedScore(breakdown),
            logs = listOf(MirrorLog(iter = 0, level = level, tag = "UnifiedCheck", message = "$msg (${elapsedMs}ms)")),
        )
    }

    private fun checkC3Family(
        p: Problem,
        schedule: Array<IntArray>,
        list: List<C3>,
        key: String,
        forbidden: Boolean,
        inc: (String) -> Unit,
        mark: (Int, Int, String) -> Unit,
    ) {
        for (c in list) {
            val seq = c.seq
            val d = seq.size
            if (d == 0 || d > p.T) continue
            for (i in 0 until p.S) {
                var j = 0
                while (j <= p.T - d) {
                    if (schedule[i][j] == seq[0]) {
                        var z = 0
                        for (l in 1 until d) if (schedule[i][j + l] == seq[l]) z++
                        val fire = if (forbidden) z == d - 1 else z < d - 1
                        if (fire) {
                            inc(key)
                            for (l in 0 until d) mark(i, j + l, key)
                        }
                    }
                    j++
                }
            }
        }
    }


    private fun weightedScore(b: Map<String, Int>): Double {
        fun w(key: String, weight: Double): Double = (b[key] ?: 0).toDouble() * weight
        var out = 0.0
        // HARD / lim tiers kept on the native scale so the single Double preserves the
        // Web tier ordering (guard/displayHard >> lim >> soft); the Web does this with a
        // tier vector, here folded into one weighted scalar.
        out += w("groupViol", 10000.0)
        out += w("pref", 9000.0)
        out += w("covU", 8000.0)
        out += w("c3n", 7000.0)
        out += w("low", 90.0)
        out += w("high", 45.0)
        // [HF508 / V5_WEIGHTS HF513] SOFT-family weights transcribed from the authoritative
        // Web V5_WEIGHTS (priority c3mn > c1 > c3 > c3m > c2): c1 3->4, c3 2->3, c3mn 4->12,
        // c2 120->1, c41 120->1, c42 10->1, covO 15->0.5.
        out += w("c3mn", 12.0)
        out += w("c1", 4.0)
        out += w("c3", 3.0)
        out += w("c3m", 2.0)
        out += w("c2", 1.0)
        out += w("c41", 1.0)
        out += w("c42", 1.0)
        out += w("covO", 0.5)
        return out
    }

}

fun List<List<Int>>.toIntArray2D(): Array<IntArray> {
    return Array(size) { i -> this[i].toIntArray() }
}
fun Array<IntArray>.copy2D(): Array<IntArray> {
    return Array(size) { i -> this[i].copyOf() }
}

fun Problem.canDo(staffI: Int, shiftK: Int): Boolean {
    if (staffI !in 0 until S || shiftK !in 0 until K) return false
    val g = sgrp[staffI]
    return bucket.getOrNull(g)?.contains(shiftK) == true
}

fun Problem.allowedShiftsForStaff(staffI: Int): IntArray {
    val b = bucket.getOrNull(sgrp.getOrNull(staffI) ?: -1) ?: IntArray(0)
    return if (b.isNotEmpty()) b else IntArray(K) { it }
}

fun normalizeSchedule(schedule: Array<IntArray>, p: Problem): Array<IntArray> = Array(p.S) { i ->
    IntArray(p.T) { j ->
        val k = schedule.getOrNull(i)?.getOrNull(j) ?: 0
        if (k in 0 until p.K) k else -1
    }
}

fun countMatrix(p: Problem, schedule: Array<IntArray>): Array<IntArray> {
    val out = Array(p.S) { IntArray(p.K) }
    for (i in 0 until p.S) for (j in 0 until p.T) {
        val k = schedule[i][j]
        if (k in 0 until p.K) out[i][k]++
    }
    return out
}

fun coverage(p: Problem, schedule: Array<IntArray>): Array<IntArray> {
    val out = Array(p.T) { IntArray(p.K) }
    for (i in 0 until p.S) for (j in 0 until p.T) {
        val k = schedule[i][j]
        if (k in 0 until p.K) out[j][k]++
    }
    return out
}

fun lockedMatrix(p: Problem): Array<BooleanArray> {
    val out = Array(p.S) { BooleanArray(p.T) }
    for (i in 0 until p.S) {
        for (j in 0 until p.T) {
            out[i][j] = p.wish[i][j] in 0 until p.K
        }
    }
    return out
}

fun restShiftIndex(state: MagiState): Int = state.shifts.indexOfFirst { it.kigou == "休" }.takeIf { it >= 0 } ?: 0

fun formatDay(startDate: String, offset: Int): String {
    return try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val d = fmt.parse(startDate) ?: return "${offset + 1}日"
        val cal = java.util.Calendar.getInstance(Locale.JAPAN)
        cal.time = d
        cal.add(java.util.Calendar.DATE, offset)
        val wd = "日月火水木金土"[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}($wd)"
    } catch (_: Exception) {
        "${offset + 1}日"
    }
}

fun MagiState.withSchedule(schedule: Array<IntArray>): MagiState {
    val rows = ArrayList<List<Int>>(schedule.size)
    for (row in schedule) rows.add(row.toList())
    return copy(schedule = rows)
}
