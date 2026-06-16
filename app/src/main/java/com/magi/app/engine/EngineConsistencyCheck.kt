package com.magi.app.engine

/**
 * Lightweight consistency probe for the native scoring stack.
 *
 * This is intentionally dependency-free so it can be called from debug UI, ad-hoc checks,
 * or future unit tests without introducing a test framework first.
 */
data class EngineConsistencyResult(
    val resolvedScore: Long,
    val deltaScore: Long,
    val analysisScore: Long,
    val ok: Boolean,
) {
    val message: String
        get() = if (ok) {
            "OK: evaluator=$resolvedScore / delta=$deltaScore / analysis=$analysisScore"
        } else {
            "NG: evaluator=$resolvedScore / delta=$deltaScore / analysis=$analysisScore"
        }
}

object EngineConsistencyCheck {
    fun checkInitial(problem: ResolvedProblem): EngineConsistencyResult {
        return check(problem, problem.initialAssignment())
    }

    fun check(problem: ResolvedProblem, schedule: Array<IntArray>): EngineConsistencyResult {
        val resolvedScore = ResolvedEvaluator(problem).penalty(copyOf(schedule))
        val deltaScore = DeltaEvaluator(problem).score(copyOf(schedule))
        val analysisScore = ScoreAnalyzer(problem).analyze(copyOf(schedule)).total
        return EngineConsistencyResult(
            resolvedScore = resolvedScore,
            deltaScore = deltaScore,
            analysisScore = analysisScore,
            ok = resolvedScore == deltaScore && resolvedScore == analysisScore,
        )
    }

    private fun copyOf(source: Array<IntArray>): Array<IntArray> = Array(source.size) { source[it].copyOf() }
}
