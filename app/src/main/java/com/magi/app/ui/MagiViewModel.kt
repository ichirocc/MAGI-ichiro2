package com.magi.app.ui

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.magi.app.v6.Evaluator
import com.magi.app.v6.LightMirrorOptimizer
import com.magi.app.v6.MirrorKeys
import com.magi.app.v6.Problem
import com.magi.app.v6.SaOptimizer
import com.magi.app.v6.SaParams
import com.magi.app.v6.ScheduleCsvBridge
import com.magi.app.v6.UnifiedViolationChecker
import com.magi.app.v6.ViolationReport
import com.magi.app.v6.V6PortAnalyzer
import com.magi.app.v6.V6PortReport
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.V6NativeOptimizer
import com.magi.app.v6.V6SanityPort
import com.magi.app.v6.Ws1Ops
import com.magi.app.v6.Ws1Result
import com.magi.app.v6.allowedShiftsForStaff
import com.magi.app.v6.canDo
import com.magi.app.v6.copy2D
import com.magi.app.v6.withSchedule
import com.magi.app.work.OptimizationRepository
import com.magi.app.work.OptimizationWorker
import com.magi.app.model.Range
import com.magi.app.model.C1Row
import com.magi.app.model.Group
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.C2Row
import com.magi.app.model.C3Row
import com.magi.app.model.C41Row
import com.magi.app.model.C42Row
import com.magi.app.model.MagiState
import com.magi.app.model.StateParser
import com.magi.app.v6.V6WebCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val emptyBreakdown: Map<String, Int> = MirrorKeys.all.associateWith { 0 }

data class UiState(
    val loaded: Boolean = false,
    val canUndo: Boolean = false,
    val staff: Int = 0,
    val days: Int = 0,
    val shifts: Int = 0,
    val groups: Int = 0,
    val use2: Boolean = false,
    val initHard: Long = 0,
    val initSoft: Long = 0,
    val running: Boolean = false,
    val hasResult: Boolean = false,
    val bestHard: Long = 0,
    val bestSoft: Long = 0,
    val totalViolations: Int = 0,
    val weightedScore: Double = 0.0,
    val breakdown: Map<String, Int> = emptyBreakdown,
    val violationCells: Map<String, String> = emptyMap(),
    val needViolations: Map<String, String> = emptyMap(),
    val countViolations: Map<String, String> = emptyMap(),
    val logs: List<String> = emptyList(),
    val iters: Long = 0,
    val itersPerSec: Long = 0,
    val elapsedMs: Long = 0,
    val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    val budgetSec: Int = 600,
    val softPolish: Boolean = false,
    val v6Algorithm: V6Algorithm = V6Algorithm.AUTO,
    val staffNames: List<String> = emptyList(),
    val staffGroupSymbols: List<String> = emptyList(),
    val shiftSymbols: List<String> = emptyList(),
    val shiftColorHex: List<String> = emptyList(),
    val shiftTextHex: List<String> = emptyList(),
    val schedule: List<List<Int>> = emptyList(),
    val v6: V6PortReport? = null,
    val constraintsEdited: Boolean = false,
    val structureEdited: Boolean = false,
    val message: String? = null,
    // 操作コパイロット: 満足度(0-100) / 研磨の限界 / ガチャ操作の助言
    val satisfaction: Int = 0,
    val polishExhausted: Boolean = false,
    val copilotHint: String? = null,
    val impossibleWishCount: Int = 0,
    val opLog: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(), // 他の案（採用案以外の候補サマリ）
)

class MagiViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var originalJson: String? = null
    private var state: MagiState? = null
    private var currentSchedule: Array<IntArray>? = null
    private var resultSchedule: Array<IntArray>? = null
    private var job: Job? = null
    private var checkJob: Job? = null
    private var checkSeq = 0L

    // ===== [v2.22] 自動保存・復元（端末内）と「元に戻す」 =====
    private val autosaveFile get() = getApplication<Application>().filesDir.resolve("magi_autosave.json")
    private var hydrated = false           // 復元完了前の自動保存を抑止（Web HF514 と同思想）
    private var saveJob: Job? = null
    private data class UndoSnap(val st: MagiState, val sched: Array<IntArray>)
    private val undoStack = ArrayDeque<UndoSnap>()

    // ===== 操作ログ（監査）: 追記式・新しい順・時刻/レベル付き =====
    data class OpLogEntry(val timeMs: Long, val level: String, val message: String)
    private val opLog = ArrayDeque<OpLogEntry>()
    private val opLogFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)

    /** 操作ログに1件追記し、UIへ反映（新しい順、最大300件）。 */
    private fun logOp(level: String, message: String) {
        opLog.addFirst(OpLogEntry(System.currentTimeMillis(), level, message))
        while (opLog.size > 300) opLog.removeLast()
        _ui.value = _ui.value.copy(opLog = opLog.map { "${opLogFmt.format(java.util.Date(it.timeMs))} [${it.level}] ${it.message}" })
    }

    init {
        // 起動時: 前回の自動保存があれば復元（無ければ何もしない）
        viewModelScope.launch {
            val txt = withContext(Dispatchers.IO) {
                runCatching { autosaveFile.takeIf { it.exists() }?.readText() }.getOrNull()
            }
            if (!txt.isNullOrBlank() && state == null) {
                loadAsync(txt)
            }
            hydrated = true
        }
        // バックグラウンド最適化（WorkManager）の進捗・結果を購読して画面へ反映（仕様書 §6.3）
        viewModelScope.launch {
            OptimizationRepository.progress.collect { p ->
                if (p != null && _ui.value.running) {
                    _ui.value = _ui.value.copy(
                        bestHard = p.hard.toLong(), bestSoft = p.soft.toLong(),
                        totalViolations = p.total, iters = p.iters, elapsedMs = p.elapsedMs,
                        message = "バックグラウンド ${p.phase}",
                    )
                }
            }
        }
        viewModelScope.launch {
            OptimizationRepository.result.collect { r -> if (r != null) applyBgResult(r) }
        }
    }

    /** バックグラウンド（WorkManager / Expedited）で最適化を開始。完了時に通知＋画面反映。 */
    fun runInBackground() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo()
        OptimizationRepository.clear()
        OptimizationRepository.request = st0 to sched0.copy2D()
        OptimizationRepository.seconds = _ui.value.budgetSec
        OptimizationRepository.workers = _ui.value.workers
        val work = androidx.work.OneTimeWorkRequestBuilder<OptimizationWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        androidx.work.WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(OptimizationWorker.UNIQUE, androidx.work.ExistingWorkPolicy.REPLACE, work)
        _ui.value = _ui.value.copy(running = true, hasResult = false, message = "バックグラウンドで最適化を開始しました（完了時に通知）")
        logOp("I", "バックグラウンド最適化 開始 (予算${_ui.value.budgetSec}s, 並列${_ui.value.workers})")
    }

    private fun applyBgResult(r: OptimizationRepository.BgResult) {
        val st0 = state ?: return
        // 完了後に別状態を読み込んだ場合、保持された古い結果を取り違えて適用しない。
        // 次元が現在の状態と食い違う結果は破棄する。
        if (r.schedule.size != st0.staffCount || r.schedule.any { it.size != st0.dayCount }) {
            OptimizationRepository.request = null
            OptimizationRepository.publishResult(null)
            return
        }
        val sched = r.schedule.copy2D()
        currentSchedule = sched
        resultSchedule = sched
        state = st0.withSchedule(sched)
        autoSave()
        _ui.value = makeUi(state ?: st0, sched, r.report, _ui.value.copy(
            running = false, hasResult = true,
            message = "バックグラウンド最適化 完了: 必須=${r.report.hard} 合計=${r.report.total}",
        ))
        logOp("I", "バックグラウンド最適化 完了 必須=${r.report.hard} 合計=${r.report.total}")
        // 消費したらクリア（再生成時の二重適用を防ぐ）
        OptimizationRepository.request = null
        OptimizationRepository.publishResult(null)
    }

    /** 1.2秒デバウンスで状態をアプリ専用領域に保存。失敗は黙殺（次回操作で再試行）。 */
    private fun autoSave() {
        if (!hydrated) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            val json = exportJson() ?: return@launch
            withContext(Dispatchers.IO) { runCatching { autosaveFile.writeText(json) } }
        }
    }

    private fun pushUndo() {
        val st = state ?: return
        val sc = currentSchedule ?: return
        undoStack.addLast(UndoSnap(st, Array(sc.size) { sc[it].clone() }))
        while (undoStack.size > 30) undoStack.removeFirst()
        _ui.value = _ui.value.copy(canUndo = true)
    }

    private fun clearUndo() {
        undoStack.clear()
        _ui.value = _ui.value.copy(canUndo = false)
    }

    /** 直前の編集・取込・計算開始前の状態へ戻す（最大30段）。 */
    fun undo() {
        if (_ui.value.running) return
        val snap = undoStack.removeLastOrNull() ?: return
        state = snap.st
        currentSchedule = Array(snap.sched.size) { snap.sched[it].clone() }
        _ui.value = _ui.value.copy(structureEdited = true, canUndo = undoStack.isNotEmpty(), message = "1つ前に戻しました")
        logOp("I", "元に戻す")
        refreshCheck()
        autoSave()
    }

    fun load(json: String) = loadAsync(json)

    fun loadAsync(json: String) {
        job?.cancel()
        _ui.value = _ui.value.copy(running = true, message = "読込中…")
        job = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) {
                    val st = StateParser.parse(json)
                    validate(st)?.let { return@withContext Result.failure<LoadedProblem>(IllegalArgumentException(it)) }
                    val p = Problem(st)
                    val ev = Evaluator(p)
                    val init = p.initialAssignment()
                    val baseEval = ev.split(ev.fullEval(init))
                    val report = UnifiedViolationChecker.check(st, init)
                    Result.success(LoadedProblem(st, init, baseEval.first, baseEval.second, report))
                }
                loaded.fold(
                    onSuccess = { lp ->
                        originalJson = json
                        state = lp.state.withSchedule(lp.schedule)
                        currentSchedule = lp.schedule.copy2D()
                        resultSchedule = null
                        clearUndo()
                        autoSave()
                        _ui.value = makeUi(
                            st = lp.state,
                            schedule = lp.schedule,
                            report = lp.report,
                            base = _ui.value.copy(
                                loaded = true,
                                running = false,
                                hasResult = false,
                                constraintsEdited = false,
                                structureEdited = false,
                                staff = lp.state.staffCount,
                                days = lp.state.dayCount,
                                shifts = lp.state.shiftCount,
                                groups = lp.state.groupCount,
                                use2 = lp.state.use2Patterns,
                                initHard = lp.nativeHard,
                                initSoft = lp.nativeSoft,
                                iters = 0,
                                itersPerSec = 0,
                                elapsedMs = 0,
                                message = "読込完了: ${lp.state.staffCount}名 / ${lp.state.dayCount}日 / ${lp.state.shiftCount}シフト",
                            ),
                        )
                        logOp("I", "読込 ${lp.state.staffCount}名/${lp.state.dayCount}日/${lp.state.shiftCount}シフト")
                    },
                    onFailure = {
                        _ui.value = _ui.value.copy(running = false, message = "読込失敗: ${it.message}")
                    },
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "読込失敗: ${e.message}")
            }
        }
    }

    /** Returns a human-readable error message if the state is structurally invalid, else null. */
    private fun validate(st: MagiState): String? {
        if (st.staffCount == 0) return "staff が空です"
        if (st.dayCount == 0) return "schedule が空です"
        if (st.shiftCount == 0) return "shifts が空です"
        if (st.groupCount == 0) return "groups が空です"
        if (st.schedule.size != st.staffCount) return "schedule の行数が staff 数と一致しません"
        if (st.groupShift.size < st.groupCount) return "groupShift の行数が groups より少ないです"
        st.groupShift.forEachIndexed { g, row ->
            if (row.size < st.shiftCount) return "groupShift[$g] の列数が shifts より少ないです"
            if (row.take(st.shiftCount).none { it == 1 }) return "groupShift[$g] に担当可能シフトがありません"
        }
        st.groupShiftApt.forEachIndexed { g, row ->
            if (g < st.groupCount && row.isNotEmpty() && row.size < st.shiftCount) return "groupShiftApt[$g] の列数が shifts より少ないです"
        }
        st.staff.forEachIndexed { i, s ->
            if (s.groupIdx !in 0 until st.groupCount) return "staff[$i].groupIdx が範囲外です (${s.groupIdx})"
        }
        st.schedule.forEachIndexed { i, row ->
            if (row.size != st.dayCount) return "schedule[$i] の日数が不揃いです"
            row.forEachIndexed { j, k ->
                if (k != -1 && k !in 0 until st.shiftCount) return "schedule[$i][$j] のシフト番号が範囲外です ($k)"
            }
        }
        return null
    }

    /**
     * [native堅牢化] 最適化・生成の実行前に構造を検証する。期間/スタッフ/シフトの不整合や
     * 未割当グループ・範囲外シフト等があれば、クラッシュさせず理由を表示して中止する
     * （添付資料 doc#5/#6/#7 起因の事故をネイティブ側でも明示的に防止）。
     */
    private fun ensureValidForRun(st: MagiState, sched: Array<IntArray>): Boolean {
        val err = validate(st.withSchedule(sched)) ?: return true
        _ui.value = _ui.value.copy(running = false, message = "実行できません: $err。編集内容を確認してください")
        return false
    }

    fun setWorkers(n: Int) { _ui.value = _ui.value.copy(workers = n.coerceIn(1, 16)) }
    fun setBudget(sec: Int) { _ui.value = _ui.value.copy(budgetSec = sec.coerceIn(1, 1800)) }
    fun setSoftPolish(b: Boolean) { _ui.value = _ui.value.copy(softPolish = b) }
    fun setV6Algorithm(a: V6Algorithm) { _ui.value = _ui.value.copy(v6Algorithm = a) }

    fun refreshCheck() {
        val st = state ?: return
        val sched = currentSchedule?.copy2D() ?: return
        val seq = ++checkSeq
        checkJob?.cancel()
        _ui.value = _ui.value.copy(running = true, message = "違反チェック中…")
        checkJob = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleCheck(st, sched)
                if (seq != checkSeq) return@launch   // [review #6] a newer check started; drop stale result
                _ui.value = makeUi(st, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    message = "違反チェック完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ))
                logOp("I", "違反チェック 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == checkSeq) _ui.value = _ui.value.copy(running = false, message = "違反チェック失敗: ${e.message}")
            }
        }
    }

    fun generateSimple() {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (!ensureValidForRun(st, sched)) return
        pushUndo()
        _ui.value = _ui.value.copy(running = true, hasResult = false, message = "簡易作成中…")
        job = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleSimple(st.withSchedule(sched), allowImpossible = true)
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                _ui.value = makeUi(state ?: st, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    iters = 0,
                    itersPerSec = 0,
                    elapsedMs = 0,
                    message = "簡易作成完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ))
                logOp("I", "簡易作成 完了 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "簡易作成失敗: ${e.message}")
            }
        }
    }

    fun start() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st0, sched0)) return
        val runState = st0.withSchedule(sched0)
        val p = Problem(runState)
        val ev = Evaluator(p)
        val params = SaParams(
            workers = _ui.value.workers,
            budgetMs = _ui.value.budgetSec * 1000L,
            softPolish = _ui.value.softPolish,
        )
        pushUndo()
        _ui.value = _ui.value.copy(running = true, hasResult = false, message = "高速計算中…")
        var lastUiMs = 0L
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    SaOptimizer(p, ev).run(params) { pr ->
                        val now = System.currentTimeMillis()
                        if (now - lastUiMs >= 200) {
                            lastUiMs = now
                            val (h, s) = ev.split(pr.bestScore)
                            val ips = if (pr.elapsedMs > 0) pr.totalIters * 1000 / pr.elapsedMs else 0
                            _ui.value = _ui.value.copy(
                                bestHard = h,
                                bestSoft = s,
                                iters = pr.totalIters,
                                itersPerSec = ips,
                                elapsedMs = pr.elapsedMs,
                            )
                        }
                    }
                }
                val report = withContext(Dispatchers.Default) { UnifiedViolationChecker.check(runState, res.schedule) }
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = runState.withSchedule(res.schedule)
                val ips = if (res.elapsedMs > 0) res.totalIters * 1000 / res.elapsedMs else 0
                _ui.value = makeUi(state ?: runState, res.schedule, report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    iters = res.totalIters,
                    itersPerSec = ips,
                    elapsedMs = res.elapsedMs,
                    message = "高速計算完了: 必須=${report.hard} 合計=${report.total} (${res.totalIters}反復, ${res.elapsedMs}ms)",
                ))
            } catch (e: CancellationException) {
                _ui.value = _ui.value.copy(running = false, message = "停止しました")
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "最適化失敗: ${e.message}")
            } finally {
                if (_ui.value.running) _ui.value = _ui.value.copy(running = false)
            }
        }
    }

    fun runLightOptimize() {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st, sched)) return
        pushUndo()
        _ui.value = _ui.value.copy(running = true, hasResult = false, message = "軽量最適化中…")
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) { LightMirrorOptimizer.optimize(st, sched, _ui.value.budgetSec.toDouble()) }
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                val ips = if (res.elapsedMs > 0) res.iterations * 1000 / res.elapsedMs else 0
                _ui.value = makeUi(state ?: st, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    iters = res.iterations,
                    itersPerSec = ips,
                    elapsedMs = res.elapsedMs,
                    message = "軽量最適化完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ))
            } catch (e: CancellationException) {
                _ui.value = _ui.value.copy(running = false, message = "停止しました")
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "軽量最適化失敗: ${e.message}")
            }
        }
    }



    // 操作コパイロット用: 直前の実行設定と結果（ガチャ操作検知に使用）
    private var lastSettingsSig: String? = null
    private var lastResultHard: Long = -1L
    private var lastTopHardFamily: String? = null

    private fun hardFamilyJp(key: String): String = when (key) {
        "covU" -> "人員不足（必要人数）"
        "c3n" -> "禁止の並び（連勤など）"
        "pref" -> "希望シフト"
        "groupViol" -> "担当できないシフト"
        "low" -> "個人の回数下限"
        "high" -> "個人の回数上限"
        else -> key
    }

    private fun topHardFamilyJp(breakdown: Map<String, Int>): String? {
        val keys = listOf("covU", "c3n", "pref", "groupViol", "low", "high")
        val top = keys.maxByOrNull { breakdown[it] ?: 0 } ?: return null
        return if ((breakdown[top] ?: 0) > 0) hardFamilyJp(top) else null
    }

    fun runV6FullOptimize() {
        val st0 = state ?: return
        val sched0 = currentSchedule ?: return
        if (_ui.value.running) return
        if (!ensureValidForRun(st0, sched0)) return
        pushUndo()
        val sig = "${_ui.value.budgetSec}|${_ui.value.workers}|${_ui.value.v6Algorithm}|${_ui.value.softPolish}"
        val hint = if (sig == lastSettingsSig && lastResultHard > 0L)
            "前回と同じ設定での再実行です。最大の未解決は『${lastTopHardFamily ?: "未解決の制約"}』。編集タブでこれを1つ緩めると改善の可能性が高いです。"
        else null
        lastSettingsSig = sig
        _ui.value = _ui.value.copy(running = true, hasResult = false, copilotHint = hint, alternatives = emptyList(), message = "計算エンジン実行中…")
        logOp("I", "最適化 開始 (予算${_ui.value.budgetSec}s, 並列${_ui.value.workers}, 方式${_ui.value.v6Algorithm})")
        val startMs = System.currentTimeMillis()
        job = viewModelScope.launch {
            try {
                val res = V6FinalPort.handleOptimize(
                    state = st0,
                    schedule = sched0.copy2D(),
                    seconds = _ui.value.budgetSec,
                    workers = _ui.value.workers,
                    softPolish = _ui.value.softPolish,
                    requestedAlgorithm = _ui.value.v6Algorithm,
                    allowImpossible = true,
                ) { phase, report, iters, elapsed ->
                    val rep = report
                    _ui.value = _ui.value.copy(
                        bestHard = rep?.hard?.toLong() ?: _ui.value.bestHard,
                        bestSoft = rep?.soft?.toLong() ?: _ui.value.bestSoft,
                        totalViolations = rep?.total ?: _ui.value.totalViolations,
                        iters = iters,
                        itersPerSec = if (elapsed > 0) iters * 1000 / elapsed else 0,
                        elapsedMs = elapsed,
                        message = "V6 $phase 実行中…",
                    )
                }
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st0.withSchedule(res.schedule)
                _ui.value = makeUi(state ?: st0, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    itersPerSec = if (_ui.value.elapsedMs > 0) _ui.value.iters * 1000 / _ui.value.elapsedMs else 0,
                    message = "最適化（${res.phase}）完了: 必須=${res.report.hard} 合計=${res.report.total} (${System.currentTimeMillis() - startMs}ms)",
                ))
                lastResultHard = res.report.hard.toLong()
                lastTopHardFamily = if (res.report.hard > 0) topHardFamilyJp(res.report.breakdown) else null
                logOp(if (res.report.hard == 0) "I" else "W", "最適化 完了 必須=${res.report.hard} 合計=${res.report.total} (${res.phase})")
                captureAlternatives()
            } catch (e: CancellationException) {
                _ui.value = _ui.value.copy(running = false, message = "停止しました")
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "V6最適化失敗: ${e.message}")
            } finally {
                if (_ui.value.running) _ui.value = _ui.value.copy(running = false)
            }
        }
    }

    fun stop() { job?.cancel(); checkJob?.cancel() }

    /** Shift indices a staff member may take (for the cell-edit bottom sheet). */
    fun allowedShiftsFor(i: Int): IntArray {
        val st = state ?: return IntArray(0)
        return Problem(st).allowedShiftsForStaff(i)
    }

    /** 入力ガイド（月次/年次の入力手順）用の各項目の件数。 */
    data class SetupCounts(
        val days: Int, val staff: Int, val shifts: Int, val groups: Int,
        val wishes: Int, val needDay: Int, val constraints: Int, val ranges: Int, val use2: Boolean,
    )
    fun setupCounts(): SetupCounts {
        val st = state ?: return SetupCounts(0, 0, 0, 0, 0, 0, 0, 0, false)
        val cons = st.cons1.size + st.cons2.size + st.cons3.size + st.cons3n.size +
            st.cons3m.size + st.cons3mn.size + st.cons41.size + st.cons42.size
        return SetupCounts(
            st.dayCount, st.staffCount, st.shiftCount, st.groupCount,
            st.wishes.size, st.needDay1.size + st.needDay2.size, cons, st.staffRange.size, st.use2Patterns,
        )
    }

    /** 担当外（そのスタッフのグループで担当不可）な希望の件数。希望で上書き時の確認に使う。 */
    fun wishOutOfScopeCount(): Int {
        val st = state ?: return 0
        val p = Problem(st)
        var n = 0
        for ((key, k) in st.wishes) {
            val i = key.split(',').getOrNull(0)?.toIntOrNull() ?: continue
            if (i in 0 until p.S && k in 0 until p.K && !p.canDo(i, k)) n++
        }
        return n
    }

    /**
     * 希望シフトを勤務表へ上書き反映（Web版の「希望で上書き」相当）。担当外の希望は
     * [includeOutOfScope]=true のときのみ反映。Undo・操作ログ付き。
     */
    fun applyWishes(includeOutOfScope: Boolean) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        val p = Problem(st)
        pushUndo()
        var applied = 0
        var oos = 0
        for ((key, k) in st.wishes) {
            val parts = key.split(',')
            val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val j = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (i !in 0 until p.S || j !in 0 until p.T || k !in 0 until p.K) continue
            val can = p.canDo(i, k)
            if (!can && !includeOutOfScope) continue
            if (i in sched.indices && j in sched[i].indices && sched[i][j] != k) {
                sched[i][j] = k
                applied++
                if (!can) oos++
            }
        }
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        val note = if (oos > 0) "（担当外 ${oos}件含む）" else ""
        logOp(if (oos > 0) "W" else "I", "希望を勤務表へ反映 ${applied}件$note")
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "希望を反映: ${applied}件$note",
        )
        refreshCheck()
    }

    private var alternativeScheds: List<Array<IntArray>> = emptyList()

    /** 直近の並列最適化で得た「他の案」を取り込み、サマリをUIへ反映。 */
    private fun captureAlternatives() {
        val st = state ?: return
        val alts = V6NativeOptimizer.lastAlternatives.map { it.copy2D() }
        alternativeScheds = alts
        val summaries = alts.mapIndexed { idx, sch ->
            val rep = UnifiedViolationChecker.check(st, sch)
            "案${idx + 1}: 必須=${rep.hard} 合計=${rep.total}"
        }
        _ui.value = _ui.value.copy(alternatives = summaries)
    }

    /** 「他の案」を勤務表へ適用（Undo・操作ログ付き）。 */
    fun applyAlternative(i: Int) {
        val st = state ?: return
        val sch = alternativeScheds.getOrNull(i)?.copy2D() ?: return
        pushUndo()
        currentSchedule = sch
        resultSchedule = sch
        state = st.withSchedule(sch)
        autoSave()
        val rep = UnifiedViolationChecker.check(state ?: st, sch)
        _ui.value = makeUi(state ?: st, sch, rep, _ui.value.copy(hasResult = true, message = "他の案 ${i + 1} を適用"))
        logOp("I", "他の案 ${i + 1} を適用 必須=${rep.hard} 合計=${rep.total}")
    }

    /** Set a specific shift in a cell (bottom-sheet picker). */
    fun setCell(i: Int, j: Int, shift: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (i !in sched.indices || j !in sched[i].indices) return
        if (sched[i][j] == shift) return
        pushUndo()
        sched[i][j] = shift
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "${st.staff.getOrNull(i)?.name ?: i} / ${j + 1}日 を ${st.shifts.getOrNull(shift)?.kigou ?: shift} に変更",
        )
        refreshCheck()
    }

    fun cycleCell(i: Int, j: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        if (i !in sched.indices || j !in sched[i].indices) return
        val p = Problem(st)
        val allowed = p.allowedShiftsForStaff(i)
        if (allowed.isEmpty()) return
        val old = sched[i][j]
        val idx = allowed.indexOf(old)
        val next = allowed[(idx + 1).floorMod(allowed.size)]
        pushUndo()
        sched[i][j] = next
        currentSchedule = sched
        state = st.withSchedule(sched)
        autoSave()
        _ui.value = _ui.value.copy(
            hasResult = true,
            schedule = sched.map { it.toList() },
            message = "${st.staff.getOrNull(i)?.name ?: i} / ${j + 1}日 を ${st.shifts.getOrNull(next)?.kigou ?: next} に変更",
        )
        refreshCheck()
    }

    // ---- constraint editing (ws3-5) -------------------------------------------

    /** A constraint family with its rows rendered for display (key used for add/remove). */
    data class ConstraintFamilyView(val key: String, val title: String, val rows: List<String>)

    fun shiftKigouList(): List<String> = state?.shifts?.map { it.kigou } ?: emptyList()

    // ---- ws2: 日別の必要人数（例外） needDay1/needDay2 の疎な上書きを編集 ----
    data class NeedDayView(val k: Int, val j: Int, val kigou: String, val p1: String, val p2: String)

    fun needDayOverrides(): List<NeedDayView> {
        val st = state ?: return emptyList()
        val keys = (st.needDay1.keys + st.needDay2.keys).toSet()
        return keys.mapNotNull { key ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val k = parts[0].toIntOrNull() ?: return@mapNotNull null
            val j = parts[1].toIntOrNull() ?: return@mapNotNull null
            NeedDayView(k, j, st.shifts.getOrNull(k)?.kigou ?: k.toString(), st.needDay1[key] ?: "", st.needDay2[key] ?: "")
        }.sortedWith(compareBy({ it.j }, { it.k }))
    }

    fun setNeedDay(k: Int, j: Int, p1: String, p2: String) {
        val st = state ?: return
        val key = "$k,$j"
        val nd1 = st.needDay1.toMutableMap()
        val nd2 = st.needDay2.toMutableMap()
        if (p1.isBlank()) nd1.remove(key) else nd1[key] = p1.trim()
        if (p2.isBlank()) nd2.remove(key) else nd2[key] = p2.trim()
        applyStructure(st.copy(needDay1 = nd1, needDay2 = nd2))
    }

    fun removeNeedDay(k: Int, j: Int) {
        val st = state ?: return
        val key = "$k,$j"
        applyStructure(st.copy(needDay1 = st.needDay1 - key, needDay2 = st.needDay2 - key))
    }

    // ---- ws5: 個人別の回数（LimMin/LimMax） staffRange["i,k"]=Range(lo,hi) を編集 ----
    data class StaffRangeView(val i: Int, val k: Int, val staffName: String, val kigou: String, val lo: String, val hi: String)

    fun staffRangeOverrides(): List<StaffRangeView> {
        val st = state ?: return emptyList()
        return st.staffRange.mapNotNull { (key, r) ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val i = parts[0].toIntOrNull() ?: return@mapNotNull null
            val k = parts[1].toIntOrNull() ?: return@mapNotNull null
            StaffRangeView(i, k, st.staff.getOrNull(i)?.name ?: i.toString(), st.shifts.getOrNull(k)?.kigou ?: k.toString(), r.lo, r.hi)
        }.sortedWith(compareBy({ it.i }, { it.k }))
    }

    fun setStaffRange(i: Int, k: Int, lo: String, hi: String) {
        val st = state ?: return
        val key = "$i,$k"
        val m = st.staffRange.toMutableMap()
        if (lo.isBlank() && hi.isBlank()) m.remove(key) else m[key] = Range(lo.trim(), hi.trim())
        applyStructure(st.copy(staffRange = m))
    }

    fun removeStaffRange(i: Int, k: Int) {
        val st = state ?: return
        applyStructure(st.copy(staffRange = st.staffRange - "$i,$k"))
    }

    // ---- ws3 移植: 希望シフト wishes["i,j"]=シフトindex（採点=pref/hard1。割当やcons3系とは別。UIのみ・モデル/エンジン不変）----
    data class WishView(val i: Int, val j: Int, val staffName: String, val day: Int, val kigou: String, val k: Int)

    fun wishOverrides(): List<WishView> {
        val st = state ?: return emptyList()
        return st.wishes.mapNotNull { (key, k) ->
            val parts = key.split(",")
            if (parts.size != 2) return@mapNotNull null
            val i = parts[0].toIntOrNull() ?: return@mapNotNull null
            val j = parts[1].toIntOrNull() ?: return@mapNotNull null
            WishView(i, j, st.staff.getOrNull(i)?.name ?: i.toString(), j + 1, st.shifts.getOrNull(k)?.kigou ?: k.toString(), k)
        }.sortedWith(compareBy({ it.i }, { it.j }))
    }

    fun setWish(i: Int, j: Int, k: Int) {
        val st = state ?: return
        val m = st.wishes.toMutableMap()
        m["$i,$j"] = k
        applyStructure(st.copy(wishes = m))
    }

    fun removeWish(i: Int, j: Int) {
        val st = state ?: return
        applyStructure(st.copy(wishes = st.wishes - "$i,$j"))
    }

    // ---- colors: シフトの表示色 shiftColors[kigou]="#rrggbb"（表示専用）----
    data class ShiftColorView(val kigou: String, val name: String, val hex: String, val custom: Boolean)

    fun shiftColorList(): List<ShiftColorView> {
        val st = state ?: return emptyList()
        return st.shifts.map { sh ->
            val ov = st.shiftColors[sh.kigou]
            ShiftColorView(sh.kigou, sh.name, V6WebCompat.resolveShiftColor(sh.kigou, sh.name, ov), !ov.isNullOrBlank())
        }
    }

    fun setShiftColor(kigou: String, hex: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        val m = st.shiftColors.toMutableMap()
        m[kigou] = hex.trim()
        applyStructure(st.copy(shiftColors = m))
    }

    fun resetShiftColor(kigou: String) {
        val st = state ?: return
        applyStructure(st.copy(shiftColors = st.shiftColors - kigou))
    }
    fun groupKigouList(): List<String> = state?.groups?.map { it.kigou } ?: emptyList()

    fun constraintFamilies(): List<ConstraintFamilyView> {
        val st = state ?: return emptyList()
        fun seq(p: List<String>) = p.filter { it.isNotBlank() }.joinToString(" -> ").ifEmpty { "(空)" }
        return listOf(
            ConstraintFamilyView("cons1", "期間の要件（N日内の必要数）",
                st.cons1.map { "${it.day1}日窓に ${it.shiftKigou} を ${it.day2} 回以上" }),
            ConstraintFamilyView("cons2", "個人の合計回数",
                st.cons2.map { "${it.shiftKigou} を合計 ${it.count} 回以上" }),
            ConstraintFamilyView("cons3", "必須の並び", st.cons3.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3n", "禁止の並び", st.cons3n.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3m", "推奨の並び", st.cons3m.map { seq(it.pattern) }),
            ConstraintFamilyView("cons3mn", "回避の並び", st.cons3mn.map { seq(it.pattern) }),
            ConstraintFamilyView("cons41", "群レンジ",
                st.cons41.map { "群 ${it.groupKigou} の ${it.shiftKigou}/日 を [${it.l.ifBlank { "-" }}, ${it.u.ifBlank { "-" }}]" }),
            ConstraintFamilyView("cons42", "群ペアの競合",
                st.cons42.map { "群 ${it.g1Kigou} の ${it.s1Kigou} と 群 ${it.g2Kigou} の ${it.s2Kigou}" }),
        )
    }

    fun addCons1(day1: String, shiftKigou: String, day2: String) {
        val st = state ?: return
        mutateConstraints(st.copy(cons1 = st.cons1 + C1Row(day1.trim(), shiftKigou, day2.trim())))
    }

    fun addCons2(shiftKigou: String, count: String) {
        val st = state ?: return
        mutateConstraints(st.copy(cons2 = st.cons2 + C2Row(shiftKigou, count.trim())))
    }

    fun addCons41(groupKigou: String, shiftKigou: String, l: String, u: String) {
        val st = state ?: return
        mutateConstraints(st.copy(cons41 = st.cons41 + C41Row(groupKigou, shiftKigou, l.trim(), u.trim())))
    }

    fun addCons42(g1: String, g2: String, s1: String, s2: String) {
        val st = state ?: return
        mutateConstraints(st.copy(cons42 = st.cons42 + C42Row(g1, g2, s1, s2)))
    }

    fun addCons3(family: String, pattern: List<String>) {
        val st = state ?: return
        // Level Zero loads cons3 by reading day columns until the first blank (truncate at
        // first blank, max 5 days), not by removing all blanks. Match that here.
        val pat = pattern.map { it.trim() }.takeWhile { it.isNotEmpty() }.take(5)
        if (pat.isEmpty()) return
        mutateConstraints(
            when (family) {
                "cons3" -> st.copy(cons3 = st.cons3 + C3Row(pat))
                "cons3n" -> st.copy(cons3n = st.cons3n + C3Row(pat))
                "cons3m" -> st.copy(cons3m = st.cons3m + C3Row(pat))
                "cons3mn" -> st.copy(cons3mn = st.cons3mn + C3Row(pat))
                else -> return
            }
        )
    }

    fun removeConstraint(family: String, index: Int) {
        val st = state ?: return
        fun <T> List<T>.without(i: Int) = filterIndexed { idx, _ -> idx != i }
        mutateConstraints(
            when (family) {
                "cons1" -> st.copy(cons1 = st.cons1.without(index))
                "cons2" -> st.copy(cons2 = st.cons2.without(index))
                "cons3" -> st.copy(cons3 = st.cons3.without(index))
                "cons3n" -> st.copy(cons3n = st.cons3n.without(index))
                "cons3m" -> st.copy(cons3m = st.cons3m.without(index))
                "cons3mn" -> st.copy(cons3mn = st.cons3mn.without(index))
                "cons41" -> st.copy(cons41 = st.cons41.without(index))
                "cons42" -> st.copy(cons42 = st.cons42.without(index))
                else -> return
            }
        )
    }

    /** Apply an edited state (constraints changed), then re-run the unified check on the current table. */
    private fun mutateConstraints(newState: MagiState?) {
        val ns = newState ?: return
        state = ns
        _ui.value = _ui.value.copy(constraintsEdited = true)
        refreshCheck()
    }

    // ---- ws1 initial setup ----------------------------------------------------

    /** Snapshot of the ws1 (初期設定) data for the editor. Recomputed per call (cheap). */
    data class Ws1View(
        val startDate: String, val endDate: String, val days: Int, val use2: Boolean,
        val shifts: List<Shift>, val groups: List<Group>, val staff: List<Staff>,
        val groupShift: List<List<Int>>,
    )

    fun ws1(): Ws1View? {
        val st = state ?: return null
        val days = currentSchedule?.firstOrNull()?.size ?: st.dayCount
        return Ws1View(st.startDate, st.endDate, days, st.use2Patterns, st.shifts, st.groups, st.staff, st.groupShift)
    }

    private fun applyStructure(ns: MagiState) {
        pushUndo()
        state = ns
        _ui.value = _ui.value.copy(structureEdited = true)
        refreshCheck()
        autoSave()
    }

    private fun applyStructure(r: Ws1Result) {
        pushUndo()
        state = r.state
        currentSchedule = r.schedule
        _ui.value = _ui.value.copy(structureEdited = true)
        refreshCheck()
        autoSave()
    }

    fun ws1EditShift(k: Int, name: String, kigou: String, need1: String, need2: String) {
        val st = state ?: return
        applyStructure(Ws1Ops.editShift(st, k, name.trim(), kigou.trim(), need1.trim(), need2.trim()))
    }

    fun ws1EditGroup(g: Int, name: String, kigou: String) {
        val st = state ?: return
        applyStructure(Ws1Ops.editGroup(st, g, name.trim(), kigou.trim()))
    }

    fun ws1EditStaff(i: Int, name: String, groupIdx: Int) {
        val st = state ?: return
        applyStructure(Ws1Ops.editStaff(st, i, name.trim(), groupIdx))
    }

    fun ws1SetGroupShift(g: Int, k: Int, allowed: Boolean) {
        val st = state ?: return
        applyStructure(Ws1Ops.setGroupShift(st, g, k, allowed))
    }

    fun ws1SetUse2(on: Boolean) {
        val st = state ?: return
        applyStructure(Ws1Ops.setUse2(st, on))
    }

    fun ws1AddShift(name: String, kigou: String, need1: String, need2: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        applyStructure(Ws1Ops.addShift(st, name.trim(), kigou.trim(), need1.trim(), need2.trim()))
    }

    fun ws1AddGroup(name: String, kigou: String) {
        val st = state ?: return
        if (kigou.isBlank()) return
        applyStructure(Ws1Ops.addGroup(st, name.trim(), kigou.trim()))
    }

    fun ws1AddStaff(name: String, groupIdx: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.addStaff(st, sched, name.trim(), groupIdx))
    }

    fun ws1ResizeDays(newT: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.resizeDays(st, sched, newT))
    }

    fun ws1CanRemoveGroup(g: Int): Boolean = state?.let { Ws1Ops.canRemoveGroup(it, g) } ?: false

    fun ws1RemoveShift(k: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.removeShift(st, sched, k))
    }

    fun ws1RemoveStaff(i: Int) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        applyStructure(Ws1Ops.removeStaff(st, sched, i))
    }

    fun ws1RemoveGroup(g: Int) {
        val st = state ?: return
        if (!Ws1Ops.canRemoveGroup(st, g)) return
        applyStructure(Ws1Ops.removeGroup(st, g))
    }

    /** Current JSON to export. ws1 edits -> full serialize; constraint edits -> overwrite cons; else schedule only. */
    fun exportJson(): String? {
        val sched = currentSchedule ?: resultSchedule ?: return null
        val st = state
        if (_ui.value.structureEdited && st != null) return StateParser.serialize(st, sched)
        val orig = originalJson ?: return null
        return if (_ui.value.constraintsEdited && st != null) StateParser.exportWithEdits(orig, st, sched)
        else StateParser.exportWithSchedule(orig, sched)
    }

    fun exportCsv(): String? {
        val st = state ?: return null
        val sched = currentSchedule ?: return null
        return ScheduleCsvBridge.build(st, sched)
    }

    /** Operator log as a plain-text file (mirrors the Web "ログ出力"). */
    fun exportLogs(): String? {
        val ops = _ui.value.opLog
        val logs = _ui.value.logs
        if (ops.isEmpty() && logs.isEmpty()) return null
        val ts = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN).format(java.util.Date())
        return buildString {
            append("MAGI ログ (Native)  出力: ").append(ts).append('\n')
            append("状態: ${_ui.value.staff}名/${_ui.value.days}日 ・ 必須=${_ui.value.bestHard} 合計=${_ui.value.totalViolations}\n")
            append("\n==== 操作ログ（新しい順 ${ops.size}件）====\n")
            ops.forEach { append(it).append('\n') }
            append("\n==== 診断ログ（${logs.size}件）====\n")
            logs.forEach { append(it).append('\n') }
        }
    }

    /** 操作ログ・診断ログ・現在の違反サマリを構造化JSONで書き出す（監査用）。 */
    fun exportLogsJson(): String? {
        if (_ui.value.opLog.isEmpty() && _ui.value.logs.isEmpty()) return null
        val o = org.json.JSONObject()
        o.put("exportedAt", System.currentTimeMillis())
        o.put("staff", _ui.value.staff); o.put("days", _ui.value.days); o.put("shifts", _ui.value.shifts)
        o.put("hard", _ui.value.bestHard); o.put("soft", _ui.value.bestSoft); o.put("total", _ui.value.totalViolations)
        o.put("satisfaction", _ui.value.satisfaction)
        o.put("opLog", org.json.JSONArray().apply { _ui.value.opLog.forEach { put(it) } })
        o.put("diagLog", org.json.JSONArray().apply { _ui.value.logs.forEach { put(it) } })
        o.put("breakdown", org.json.JSONObject().apply { _ui.value.breakdown.forEach { (k, v) -> put(k, v) } })
        return o.toString(2)
    }

    fun importCsv(text: String) {
        val st = state ?: return
        val sched = currentSchedule ?: return
        pushUndo()
        _ui.value = _ui.value.copy(running = true, message = "CSV取込中…")
        job = viewModelScope.launch {
            try {
                val res = withContext(Dispatchers.Default) { ScheduleCsvBridge.parse(text, st, sched) }
                currentSchedule = res.schedule.copy2D()
                autoSave()
                resultSchedule = res.schedule.copy2D()
                state = st.withSchedule(res.schedule)
                _ui.value = makeUi(state ?: st, res.schedule, res.report, _ui.value.copy(
                    running = false,
                    hasResult = true,
                    message = "CSV取込完了: 必須=${res.report.hard} 合計=${res.report.total}",
                ))
                logOp("I", "CSV取込 完了 必須=${res.report.hard} 合計=${res.report.total}")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(running = false, message = "CSV取込失敗: ${e.message}")
            }
        }
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }

    private fun makeUi(st: MagiState, schedule: Array<IntArray>, report: ViolationReport, base: UiState): UiState {
        val groupSymbols = st.staff.map { staff -> st.groups.getOrNull(staff.groupIdx)?.kigou ?: "" }
        val v6 = V6PortAnalyzer.analyze(st, schedule, report)
        val sanity = V6SanityPort.build(st, schedule)
        val v6Logs = listOf("[I] LoadDataBit: ${sanity.loadDataBitSummary}") + sanity.warns.map { "[W] SanityCheck: $it" } + sanity.notes.map { "[I] V6Port: $it" } + sanity.duplicateSeqConstraints.take(4).map { "[W] DuplicateSeq: $it" }
        // 満足度(0-100): 初期からの違反削減率。HARD未解決の間は上限を抑える。
        val initTotal = (base.initHard + base.initSoft).coerceAtLeast(1L)
        val ratio = (1.0 - report.total.toDouble() / initTotal).coerceIn(0.0, 1.0)
        val sat = if (report.hard > 0) (ratio * 55).toInt() else (40 + (ratio * 60).toInt()).coerceIn(0, 100)
        return base.copy(
            staff = st.staffCount,
            days = st.dayCount,
            shifts = st.shiftCount,
            groups = st.groupCount,
            use2 = st.use2Patterns,
            bestHard = report.hard.toLong(),
            bestSoft = report.soft.toLong(),
            totalViolations = report.total,
            weightedScore = report.weightedScore,
            breakdown = emptyBreakdown + report.breakdown,
            violationCells = report.violations,
            needViolations = report.needViolations,
            countViolations = report.countViolations,
            logs = v6Logs + report.logs.map { "[${it.level}] ${it.tag}: ${it.message}" },
            staffNames = st.staff.map { it.name },
            staffGroupSymbols = groupSymbols,
            shiftSymbols = st.shifts.map { it.kigou },
            shiftColorHex = st.shifts.map { V6WebCompat.resolveShiftColor(it.kigou, it.name, st.shiftColors[it.kigou]) },
            shiftTextHex = st.shifts.map { V6WebCompat.pickTextColor(V6WebCompat.resolveShiftColor(it.kigou, it.name, st.shiftColors[it.kigou])) },
            schedule = schedule.map { it.toList() },
            v6 = v6,
            satisfaction = sat,
            // 研磨の限界: 必須は解決済みだが微調整が残る → 手修正の検討を促す
            polishExhausted = report.hard == 0 && report.total > 0,
            // 解決したらガチャ助言は消す
            copilotHint = if (report.hard == 0) null else base.copilotHint,
            // 担当外など実現不能な希望（Web版の担当外希望警告に相当）
            impossibleWishCount = sanity.impossibleWishes.size,
        )
    }

    private data class LoadedProblem(
        val state: MagiState,
        val schedule: Array<IntArray>,
        val nativeHard: Long,
        val nativeSoft: Long,
        val report: ViolationReport,
    )
}

private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m
