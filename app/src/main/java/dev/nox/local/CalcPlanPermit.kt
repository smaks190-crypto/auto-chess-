package dev.nox.local

/** Ephemeral, single-task approval. No persistent grant or model-generated scope. */
internal class CalcPlanPermit(
    private val pkg: String,
    private val window: Int,
    resources: List<String>,
    private val deadlineMs: Long,
    private val now: () -> Long
) {
    private val sequence = resources.toList()
    private var index = 0
    private var revoked = false
    private var pending = false
    init { require(sequence.isNotEmpty() && sequence.size <= 26) }
    @Synchronized fun allows(pkg: String, window: Int, resource: String): Boolean =
        !revoked && !pending && now() < deadlineMs && this.pkg == pkg &&
            this.window == window && sequence.getOrNull(index) == resource
    @Synchronized fun dispatch(pkg: String, window: Int, resource: String): Boolean {
        if (!allows(pkg, window, resource)) { revoked = true; return false }
        pending = true
        return true
    }
    @Synchronized fun verified(): Boolean {
        if (revoked || !pending) { revoked = true; return false }
        index++
        pending = false
        return true
    }
    @Synchronized fun complete(): Boolean = !revoked && !pending && index == sequence.size
    @Synchronized fun revoke() { revoked = true }
}
