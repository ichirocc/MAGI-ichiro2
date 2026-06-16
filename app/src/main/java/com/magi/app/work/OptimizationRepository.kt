package com.magi.app.work

import com.magi.app.model.MagiState
import com.magi.app.v6.ViolationReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between the UI/ViewModel and the background [OptimizationWorker]
 * (改善仕様書 §6.3). The MagiState is far larger than WorkManager's 10KB inputData limit,
 * so the input is handed over here by reference and progress/result are published as flows.
 *
 * While the process is alive (incl. backgrounded), this carries everything. If the process
 * is killed mid-run the Worker can no longer find its input and fails gracefully — the last
 * autosaved state is then reloaded on relaunch.
 */
object OptimizationRepository {
    data class BgProgress(
        val phase: String,
        val hard: Int,
        val soft: Int,
        val total: Int,
        val iters: Long,
        val elapsedMs: Long,
    )

    data class BgResult(
        val schedule: Array<IntArray>,
        val report: ViolationReport,
        val phase: String,
    )

    /** Input handed to the next worker run. */
    @Volatile var request: Pair<MagiState, Array<IntArray>>? = null
    @Volatile var seconds: Int = 60
    @Volatile var workers: Int = 4

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _progress = MutableStateFlow<BgProgress?>(null)
    val progress: StateFlow<BgProgress?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<BgResult?>(null)
    val result: StateFlow<BgResult?> = _result.asStateFlow()

    fun setRunning(v: Boolean) { _running.value = v }
    fun publishProgress(p: BgProgress) { _progress.value = p }
    fun publishResult(r: BgResult?) { _result.value = r }
    fun clear() { _progress.value = null; _result.value = null }
}
