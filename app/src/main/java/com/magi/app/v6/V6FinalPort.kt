package com.magi.app.v6

import com.magi.app.model.MagiState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Final bridge for Web App-level handlers.
 *
 * V6 Web kept many behaviors inside React App methods instead of standalone
 * worker functions: handleSimple, handleCheck, handleOptimize, busy-detail
 * construction, impossible-wish gate, algorithm labels, and post-optimization
 * HF80/HF67/HF66/HF70 chaining.  This object ports those App-level semantics as
 * pure Kotlin so ViewModel/Compose can call the same workflow without WebView.
 */
object V6FinalPort {
    data class BusyDetail(
        val algorithm: String,
        val base: String = algorithm,
        val problemSize: String,
        val constraintCount: String,
        val subtitle: String = "",
        val phaseDesc: String = "",
        val expectedSec: String = "",
        val estimatedIter: String = "",
        val uiFrozen: Boolean = false,
        val startedAt: Long = System.currentTimeMillis(),
    )

    data class AlgorithmLabel(val icon: String, val name: String, val desc: String, val tech: String)

    sealed class OptimizationPlan {
        data class V5(val seconds: Int) : OptimizationPlan()
        data class ALNS(val seconds: Int, val restarts: Int) : OptimizationPlan()
        data class RSIThenALNS(val rsiSec: Int, val alnsSec: Int, val alnsRestarts: Int) : OptimizationPlan()
        data class RSIPlus(val seconds: Int) : OptimizationPlan()
    }

    data class ImpossibleWishGate(
        val allowed: Boolean,
        val count: Int,
        val message: String,
        val logs: List<MirrorLog>,
    )

    data class ActionResult(
        val schedule: Array<IntArray>,
        val report: ViolationReport,
        val phase: String,
        val busyDetail: BusyDetail,
        val logs: List<MirrorLog>,
        val post: V6PostOptimizationResult? = null,
    )

    fun buildBusyDetail(state: MagiState, algorithm: String, overrides: Map<String, String> = emptyMap()): BusyDetail {
        val n = state.staffCount
        val t = state.dayCount
        val k = state.shiftCount
        val totalHardCons = state.cons1.size + state.cons2.size + state.cons3.size + state.cons3n.size + state.cons41.size + state.cons42.size
        val totalSoftCons = state.cons3m.size + state.cons3mn.size
        val wishCount = state.wishes.size
        return BusyDetail(
            algorithm = algorithm,
            problemSize = "${n}名 × ${t}日 × ${k}シフト = ${n * t * k} セル",
            constraintCount = "HARD ${totalHardCons}件 / SOFT ${totalSoftCons}件 / 希望 ${wishCount}件",
            subtitle = overrides["subtitle"].orEmpty(),
            phaseDesc = overrides["phaseDesc"].orEmpty(),
            expectedSec = overrides["expectedSec"].orEmpty(),
            estimatedIter = overrides["estimatedIter"].orEmpty(),
            uiFrozen = overrides["uiFrozen"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    fun confirmDespiteImpossibleWishes(state: MagiState, allowImpossible: Boolean = false): ImpossibleWishGate {
        val imp = V6SanityPort.detectImpossibleWishes(state)
        if (imp.isEmpty()) return ImpossibleWishGate(true, 0, "不可能希望なし", emptyList())
        val lines = imp.groupBy { it.staffName }.entries.take(12).map { (name, rows) ->
            "・$name: ${rows.size}件 (${rows.take(3).joinToString { "${it.dayIndex + 1}日=${it.shiftSymbol}" }})"
        }
        val msg = "不可能希望が ${imp.size}件あります。担当範囲外シフトへの希望は永久に充足できません。\n" +
            lines.joinToString("\n") +
            if (imp.size > lines.sumOf { line -> Regex("\\d+件").find(line)?.value?.removeSuffix("件")?.toIntOrNull() ?: 0 }) "\n…詳細はSanityCheckを確認" else ""
        val level = if (allowImpossible) "W" else "E"
        val logs = listOf(MirrorLog(level = level, tag = "ImpossibleWishGate", message = msg.replace("\n", " / ")))
        return ImpossibleWishGate(allowImpossible, imp.size, msg, logs)
    }

    fun optimizationPlan(seconds: Int): OptimizationPlan {
        // [review #4] Honor the user's budget: the algorithm is still chosen by range, but the
        // run time uses the requested `seconds` (previously fixed 10/30/90/150+75... regardless).
        val s = seconds.coerceAtLeast(1)
        return when {
            s <= 30 -> OptimizationPlan.V5(s)
            s <= 60 -> OptimizationPlan.ALNS(s, 1)
            s <= 180 -> OptimizationPlan.ALNS(s, 2)
            s <= 300 -> { val rsi = (s * 2) / 3; OptimizationPlan.RSIThenALNS(rsi, s - rsi, 2) }
            else -> OptimizationPlan.RSIPlus(s)
        }
    }

    fun getAlgorithmLabel(seconds: Int): AlgorithmLabel = when {
        seconds <= 10 -> AlgorithmLabel("⚡", "高速", "短時間でサッと作成", "v5")
        seconds <= 30 -> AlgorithmLabel("★", "標準", "速さと品質のバランス", "v5")
        seconds <= 60 -> AlgorithmLabel("★★", "高品質", "じっくり改善", "ALNS×1")
        seconds <= 180 -> AlgorithmLabel("★★", "推奨", "品質重視 （おすすめ）", "ALNS×2")
        seconds <= 300 -> AlgorithmLabel("🧬", "学習+研磨", "RSI重み学習→ALNS研磨 （再現性検証付き）", "RSI→ALNS")
        seconds <= 600 -> AlgorithmLabel("🌈", "究極(10分)", "短縮RSI++ (Open-ended探索→PhaseC研磨)", "RSI++")
        else -> AlgorithmLabel("🌈", "究極", "最大限の品質 (${seconds / 60}分)", "RSI++拡張")
    }

    suspend fun handleSimple(state: MagiState, allowImpossible: Boolean = false): ActionResult = withContext(Dispatchers.Default) {
        require(state.dayCount > 0) { "対象期間が無効です。終了日を開始日より後の日付にしてください" }
        val gate = confirmDespiteImpossibleWishes(state, allowImpossible)
        if (!gate.allowed) error(gate.message)
        val busy = buildBusyDetail(state, "シフト作成中", mapOf(
            "subtitle" to "初期勤務表を作成中",
            "phaseDesc" to "希望シフトと必要人数を考慮して、担当できるシフトを割り当てています",
            "expectedSec" to "< 1 秒",
            "estimatedIter" to "~800 回",
        ))
        val res = GreedyMirrorScheduler.generate(state)
        val logs = gate.logs + res.report.logs + MirrorLog(tag = "MAGI_GenerateInitial", message = "簡易作成 完了 HARD=${res.report.hard} total=${res.report.total}")
        ActionResult(res.schedule, res.report.copy(logs = logs), "simple", busy, logs)
    }

    suspend fun handleCheck(state: MagiState, schedule: Array<IntArray> = state.schedule.toIntArray2D()): ActionResult = withContext(Dispatchers.Default) {
        val busy = buildBusyDetail(state, "違反チェック中", mapOf(
            "subtitle" to "違反チェック",
            "phaseDesc" to "勤務表のすべての違反を確認しています（最適化結果は変更しません）",
            "expectedSec" to "< 0.1 秒",
            "estimatedIter" to "評価のみ （反復なし）",
        ))
        val report = UnifiedViolationChecker.check(state, schedule)
        val hc = report.hard
        val sc = report.soft
        val logs = report.logs + MirrorLog(tag = "UnifiedCheck", message = if (report.total == 0) "違反なし ✓" else "HARD ${hc}件・品質 ${sc}件")
        ActionResult(schedule.copy2D(), report.copy(logs = logs), "check", busy, logs)
    }

    suspend fun handleOptimize(
        state: MagiState,
        schedule: Array<IntArray> = state.schedule.toIntArray2D(),
        seconds: Int,
        workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
        softPolish: Boolean = false,
        requestedAlgorithm: V6Algorithm = V6Algorithm.AUTO,
        allowImpossible: Boolean = false,
        onProgress: (String, ViolationReport?, Long, Long) -> Unit = { _, _, _, _ -> },
    ): ActionResult = withContext(Dispatchers.Default) {
        require(state.dayCount > 0) { "対象期間が無効です。基本情報で終了日を開始日より後にしてください" }
        val gate = confirmDespiteImpossibleWishes(state, allowImpossible)
        if (!gate.allowed) error(gate.message)
        val label = getAlgorithmLabel(seconds)
        val plan = optimizationPlan(seconds)
        val busy = buildBusyDetail(state, label.name, mapOf(
            "subtitle" to label.desc,
            "phaseDesc" to "${label.tech} をNativeエンジンで実行中",
            "expectedSec" to "約 ${seconds} 秒",
            "estimatedIter" to "問題サイズに応じて自動調整",
        ))
        // [review #1] When the user explicitly picks an algorithm (not AUTO), honor it with the
        // full requested budget. AUTO keeps the time-budget-based plan. [review #3] postPolish=false
        // so optimize() does NOT polish internally — the single post chain below owns polishing.
        val opts = if (requestedAlgorithm != V6Algorithm.AUTO) {
            V6OptimizerOptions(requestedAlgorithm, seconds.coerceAtLeast(1), workers, softPolish, restarts = 2, postPolish = false)
        } else when (plan) {
            is OptimizationPlan.V5 -> V6OptimizerOptions(V6Algorithm.V5, plan.seconds, workers, softPolish, restarts = 1, postPolish = false)
            is OptimizationPlan.ALNS -> V6OptimizerOptions(V6Algorithm.ALNS, plan.seconds, workers, softPolish, restarts = plan.restarts, postPolish = false)
            is OptimizationPlan.RSIThenALNS -> V6OptimizerOptions(V6Algorithm.RSI, plan.rsiSec, workers, softPolish, restarts = plan.alnsRestarts, postPolish = false)
            is OptimizationPlan.RSIPlus -> V6OptimizerOptions(V6Algorithm.RSI_PLUS, plan.seconds, workers, softPolish, restarts = 2, postPolish = false)
        }
        val optsR = opts.copy(rectSwap = V6LateOperators.optFlagBool(state, "rectSwap", true))   // [HF532移植] optFlags.rectSwap 既定ON
        val first = V6NativeOptimizer.optimize(state, schedule, optsR, onProgress)
        val chained = if (requestedAlgorithm == V6Algorithm.AUTO && plan is OptimizationPlan.RSIThenALNS) {
            V6NativeOptimizer.optimize(state, first.schedule, optsR.copy(algorithm = V6Algorithm.ALNS, totalBudgetSec = plan.alnsSec), onProgress)
        } else first
        val post = V6HotfixPasses.runPostOptimization(state, chained.schedule, label.tech)
        val logs = gate.logs + first.phaseLogs + (if (chained !== first) chained.phaseLogs else emptyList()) + post.logs + post.report.logs
        ActionResult(post.schedule, post.report.copy(logs = logs), "optimize:${label.tech}", busy, logs, post)
    }

    fun checkResultWorse(before: ViolationReport?, after: ViolationReport): String? {
        if (before == null) return null
        return when {
            after.hard > before.hard -> "HARDが悪化しました: ${before.hard} -> ${after.hard}"
            after.total > before.total && after.hard >= before.hard -> "違反総数が悪化しました: ${before.total} -> ${after.total}"
            after.weightedScore > before.weightedScore && after.total >= before.total -> "重み付きスコアが悪化しました: ${before.weightedScore.toLong()} -> ${after.weightedScore.toLong()}"
            else -> null
        }
    }

    fun buildBusyLogLine(detail: BusyDetail): String = buildString {
        append(detail.algorithm)
        if (detail.subtitle.isNotBlank()) append(" — ").append(detail.subtitle)
        append(" / ").append(detail.problemSize)
        append(" / ").append(detail.constraintCount)
    }
}
