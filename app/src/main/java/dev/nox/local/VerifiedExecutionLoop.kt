package dev.nox.local

/** Immutable semantic evidence. The signature must cover every planning-relevant field. */
internal data class SemanticObservation<S : Any>(val key: PlanningState, val state: S)
internal sealed interface VerifiedDispatch<out S : Any> {
    data class Observed<S : Any>(val observation: SemanticObservation<S>) : VerifiedDispatch<S>
    object Rejected : VerifiedDispatch<Nothing>
    object Unknown : VerifiedDispatch<Nothing>
}
internal enum class LoopStatus { GOAL_VERIFIED, NO_PLAN, REJECTED, UNCERTAIN, SCOPE_CHANGED, CANCELLED, DEADLINE, STEP_LIMIT, ERROR }
internal data class LoopResult(val status: LoopStatus, val confirmed: Int, val speculativeHits: Int)

/** Bounded task-owner loop. No Android calls, no permissions, no device access here.
 * Executor must enforce live package/window/permission/approval/identity checks and
 * independently verify the action. Speculation must be pure, non-mutating, and must
 * not share a mutable model conversation with the foreground planner.
 */
internal class VerifiedExecutionLoop<S : Any, I : Any>(
    private val now: () -> Long,
    private val cancelled: () -> Boolean,
    private val decide: (SemanticObservation<S>) -> I?,
    private val predict: (SemanticObservation<S>, I) -> SemanticObservation<S>?,
    private val speculate: (SemanticObservation<S>) -> I?,
    private val validate: (SemanticObservation<S>, I) -> Boolean,
    private val dispatch: (SemanticObservation<S>, I) -> VerifiedDispatch<S>,
    private val goalVerified: (SemanticObservation<S>) -> Boolean,
    private val speculativeWaitMs: Long = 0
) {
    private data class Choice<I>(val intent: I?)
    fun run(initial: SemanticObservation<S>, deadline: Long, maxSteps: Int): LoopResult {
        require(maxSteps in 1..1000 && speculativeWaitMs in 0..1000)
        var confirmed = 0
        var hits = 0
        var actual = initial
        var candidate: I? = null
        fun result(s: LoopStatus) = LoopResult(s, confirmed, hits)
        fun stop(): LoopStatus? = when { cancelled() -> LoopStatus.CANCELLED; now() >= deadline -> LoopStatus.DEADLINE; else -> null }
        fun sameScope(a: PlanningState, b: PlanningState) = a.taskId == b.taskId && a.pkg == b.pkg && a.epoch == b.epoch
        SpeculativePlanner<Choice<I>>(now, cancelled).use { planner ->
            try {
                repeat(maxSteps) {
                    stop()?.let { return result(it) }
                    if (goalVerified(actual)) return result(LoopStatus.GOAL_VERIFIED)
                    val cached = candidate?.takeIf { validate(actual, it) }
                    candidate = null
                    val intent = cached ?: decide(actual) ?: return result(LoopStatus.NO_PLAN)
                    if (!validate(actual, intent)) return result(LoopStatus.REJECTED)
                    if (cached != null) hits++
                    stop()?.let { return result(it) }
                    val previous = actual
                    val predicted = predict(previous, intent)
                    if (predicted != null) planner.offer(previous.key, predicted.key, deadline) { Choice(speculate(predicted)) }
                    stop()?.let { return result(it) }
                    when (val outcome = try { dispatch(previous, intent) } catch (_: Exception) { return result(LoopStatus.UNCERTAIN) }) {
                        VerifiedDispatch.Rejected -> return result(LoopStatus.REJECTED)
                        VerifiedDispatch.Unknown -> return result(LoopStatus.UNCERTAIN)
                        is VerifiedDispatch.Observed -> {
                            val fresh = outcome.observation
                            if (!sameScope(previous.key, fresh.key)) return result(LoopStatus.SCOPE_CHANGED)
                            if (fresh.key.sequence <= previous.key.sequence) return result(LoopStatus.UNCERTAIN)
                            confirmed++
                            actual = fresh
                            candidate = planner.take(actual.key, verifiedPrevious = true, waitMs = speculativeWaitMs)?.intent
                        }
                    }
                }
                stop()?.let { return result(it) }
                return result(if (goalVerified(actual)) LoopStatus.GOAL_VERIFIED else LoopStatus.STEP_LIMIT)
            } catch (_: Exception) {
                return result(LoopStatus.ERROR)
            }
        }
    }
}
