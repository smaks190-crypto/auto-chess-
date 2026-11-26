package dev.nox.local

import java.security.MessageDigest

/** Only explicit user-delimited fields are accepted. Model output cannot choose arbitrary checks. */
internal data class DraftGoal(val recipient: String, val text: String)
internal class GoalTemplateRegistry {
    private val explicitDraft = Regex("""(?i)^подготовь\s+черновик\s+в\s+Telegram\s+для\s+«([^«»\r\n]{1,160})»\s*:\s*«([^«»]{1,4000})»\s*$""")
    fun draft(request: String): DraftGoal? {
        if (request.length > 4500) return null
        val m = explicitDraft.matchEntire(request.trim()) ?: return null
        val recipient = m.groupValues[1].trim()
        val text = m.groupValues[2]
        if (recipient.isBlank() || text.isBlank()) return null
        return DraftGoal(recipient,text)
    }
}

/** IDs must be provided by a trusted application adapter, never by the model. */
internal data class DraftFields(val pkg: String, val headerId: String, val composerId: String) {
    init { require(pkg.isNotBlank() && headerId.isNotBlank() && composerId.isNotBlank() && headerId != composerId) }
}
internal data class DraftField(val id: String, val text: String?, val editable: Boolean, val hint: Boolean)
internal data class DraftObservation(val seq: Long, val pkg: String, val window: Int, val nodes: List<DraftField>)
internal data class DraftEvidence(val seq: Long, val binding: String)

internal object VerifiedDraft {
    fun verify(goal: DraftGoal, fields: DraftFields, observation: DraftObservation, afterSeq: Long): DraftEvidence? {
        if (observation.seq <= afterSeq || observation.pkg != fields.pkg || observation.window < 0) return null
        if (goal.recipient.isBlank() || goal.text.isBlank()) return null
        val header = observation.nodes.singleOrNull { it.id == fields.headerId } ?: return null
        val composer = observation.nodes.singleOrNull { it.id == fields.composerId } ?: return null
        if (header.editable || header.hint || header.text != goal.recipient) return null
        if (!composer.editable || composer.hint || composer.text != goal.text) return null
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("SEND",fields.pkg,observation.window.toString(),fields.headerId,fields.composerId,goal.recipient,goal.text)
            .forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        val binding = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return DraftEvidence(observation.seq,binding)
    }
}

/** One confirmation per verified snapshot; caller must obtain actual user consent before approve. */
internal class DraftApprovalGate(private val now: () -> Long) {
    private data class Pending(val evidence: DraftEvidence, val time: Long)
    private var pending: Pending? = null
    @Synchronized fun approve(evidence: DraftEvidence) { pending = Pending(evidence,now()) }
    @Synchronized fun clear() { pending = null }
    @Synchronized fun consume(goal: DraftGoal, fields: DraftFields, fresh: DraftObservation): Boolean {
        val p = pending ?: return false
        pending = null
        val elapsed = now() - p.time
        if (elapsed !in 0..30_000L) return false
        val evidence = VerifiedDraft.verify(goal,fields,fresh,p.evidence.seq) ?: return false
        return evidence.binding == p.evidence.binding
    }
}
