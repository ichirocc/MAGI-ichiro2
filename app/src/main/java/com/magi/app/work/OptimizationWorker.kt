package com.magi.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.magi.app.v6.V6FinalPort
import com.magi.app.v6.copy2D
import kotlinx.coroutines.CancellationException

/**
 * Background optimization (改善仕様書 §6.2). Runs the V6 engine off the UI process's main
 * thread, publishes live progress to [OptimizationRepository], persists the result there, and
 * posts a completion notification. Enqueued as expedited work (with non-expedited fallback).
 */
class OptimizationWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val req = OptimizationRepository.request ?: return Result.failure()
        ensureChannel()
        OptimizationRepository.setRunning(true)
        return try {
            val res = V6FinalPort.handleOptimize(
                state = req.first,
                schedule = req.second.copy2D(),
                seconds = OptimizationRepository.seconds,
                workers = OptimizationRepository.workers,
                allowImpossible = true,
            ) { phase, report, iters, elapsed ->
                if (report != null) {
                    OptimizationRepository.publishProgress(
                        OptimizationRepository.BgProgress(phase, report.hard, report.soft, report.total, iters, elapsed),
                    )
                }
            }
            OptimizationRepository.publishResult(
                OptimizationRepository.BgResult(res.schedule, res.report, res.phase),
            )
            notifyDone(res.report.hard, res.report.total)
            Result.success()
        } catch (e: CancellationException) {
            // REPLACE による差し替え時のみ発生する。後続ワーカーが running=true を立てて
            // 引き継ぐので、ここで running をクリアすると新しい実行の進捗が握りつぶされる。
            throw e
        } catch (e: Exception) {
            android.util.Log.e("OptimizationWorker", "optimization failed", e)
            notify("最適化に失敗しました", e.message ?: e.javaClass.simpleName)
            Result.failure()
        }.also {
            OptimizationRepository.setRunning(false)
        }
    }

    /** Required for expedited work (used as a foreground fallback on older OS). */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("勤務表を最適化中")
            .setContentText("バックグラウンドで計算しています…")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(NID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NID_PROGRESS, n)
    }

    private fun notifyDone(hard: Int, total: Int) {
        val msg = if (hard == 0) "配布できます（必須違反0・合計$total）" else "未解決$hard 件（合計$total）"
        notify("最適化が完了しました", msg)
    }

    private fun notify(title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NID_DONE, n) }
    }

    private fun ensureChannel() {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "勤務表の最適化", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val UNIQUE = "magi_bg_optimize"
        private const val CHANNEL = "magi_optimize"
        private const val NID_PROGRESS = 4101
        private const val NID_DONE = 4102
    }
}
