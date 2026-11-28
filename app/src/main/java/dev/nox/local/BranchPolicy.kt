package dev.nox.local

/** A historical reply can be regenerated only when it is a completed text-model response. */
internal data class BranchMessage(val id: Long, val user: Boolean, val text: String,
                                  val state: String, val kind: String)
internal data class BranchPlan(val fromUser: Long, val text: String)
internal object BranchPolicy {
    fun plan(selected: BranchMessage, previous: BranchMessage?, replacement: String?): BranchPlan? {
        val original = if (replacement != null) {
            if (!selected.user || selected.state != "complete") return null
            selected
        } else {
            if (selected.user || selected.state != "complete" || selected.kind != "chat") return null
            previous?.takeIf { it.user && it.state == "complete" && it.id < selected.id } ?: return null
        }
        val text = (replacement ?: original.text).trim()
        if (original.id <= 0L || text.isEmpty() || text.length > 2000) return null
        return BranchPlan(original.id, text)
    }
}
