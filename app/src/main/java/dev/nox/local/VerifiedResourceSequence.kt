package dev.nox.local

/** Exact resource names derived from a validated goal, never cached node IDs. */
internal data class ResourceSequence private constructor(val remaining: List<String>) {
    companion object {
        fun of(resources: List<String>) = ResourceSequence(resources.toList())
    }
    val finished: Boolean get() = remaining.isEmpty()
    fun observation(task: String, pkg: String, window: Int, seq: Long): SemanticObservation<ResourceSequence> {
        // Length prefixes prevent ambiguous concatenation; window identity is scope.
        val signature = if (finished) "done" else remaining.joinToString("") { "${it.length}:$it" }
        return SemanticObservation(PlanningState(task, pkg, window.toLong() and 0xffffffffL, seq, signature), this)
    }
}

/** Pure resource planner. Dispatch and final verification belong to AndroidAgentTools. */
internal fun runVerifiedResources(
    initial: SemanticObservation<ResourceSequence>,
    now: () -> Long, cancelled: () -> Boolean, deadline: Long, maxSteps: Int,
    dispatch: (SemanticObservation<ResourceSequence>, String) -> VerifiedDispatch<ResourceSequence>
): LoopResult = VerifiedExecutionLoop<ResourceSequence, String>(
    now, cancelled,
    decide = { it.state.remaining.firstOrNull() },
    predict = { o, intent ->
        if (o.state.remaining.firstOrNull() != intent || o.key.sequence == Long.MAX_VALUE) null
        else ResourceSequence.of(o.state.remaining.drop(1)).observation(o.key.taskId, o.key.pkg, o.key.epoch.toInt(), o.key.sequence + 1)
    },
    speculate = { it.state.remaining.firstOrNull() },
    validate = { o, intent -> o.state.remaining.firstOrNull() == intent },
    dispatch = dispatch,
    goalVerified = { it.state.finished }
).run(initial, deadline, maxSteps)
