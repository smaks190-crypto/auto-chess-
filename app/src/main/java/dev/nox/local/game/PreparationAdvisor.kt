package dev.nox.local.game

internal enum class MatchPhase { UNKNOWN, COMBAT, PREPARATION }

internal data class ShopOffer(val hero: String, val cost: Int) {
    init {
        require(hero.isNotBlank() && cost > 0)
    }
}

/** Structured simulator observations. Null slots represent visibly empty shop slots. */
internal data class MatchFrame(
    val round: Int,
    val sequence: Long,
    val phase: MatchPhase,
    val gold: Int,
    val freeBenchSlots: Int,
    val shop: List<ShopOffer?>,
    val owned: Map<String, Int>
) {
    init {
        require(round > 0 && sequence >= 0 && gold >= 0 && freeBenchSlots >= 0)
        require(owned.all { (hero, count) -> hero.isNotBlank() && count >= 0 })
    }
}

internal data class PurchaseAdvice(val slot: Int, val offer: ShopOffer)

/** Recommendation-only baseline for a single purchase, without merges or concurrent transactions. */
internal object PreparationAdvisor {
    fun recommend(frame: MatchFrame, priority: List<String>): PurchaseAdvice? {
        if (frame.phase != MatchPhase.PREPARATION || frame.freeBenchSlots == 0) return null
        for (hero in priority) {
            for ((slot, offer) in frame.shop.withIndex()) {
                if (offer != null && offer.hero == hero && offer.cost <= frame.gold) {
                    return PurchaseAdvice(slot, offer)
                }
            }
        }
        return null
    }

    /** Require the next simulator frame to show all effects of this specific purchase. */
    fun observedPurchase(before: MatchFrame, advice: PurchaseAdvice, after: MatchFrame): Boolean {
        if (before.phase != MatchPhase.PREPARATION || after.phase != MatchPhase.PREPARATION ||
            before.round != after.round || after.sequence <= before.sequence ||
            after.sequence - before.sequence != 1L) return false

        val (slot, offer) = advice
        if (before.freeBenchSlots <= 0 || before.shop.getOrNull(slot) != offer ||
            before.shop.size != after.shop.size || after.shop[slot] != null) return false
        if (before.shop.indices.any { it != slot && before.shop[it] != after.shop[it] }) return false

        val expectedOwned = before.owned.toMutableMap()
        expectedOwned[offer.hero] = expectedOwned.getOrDefault(offer.hero, 0) + 1
        return after.gold == before.gold - offer.cost &&
            after.freeBenchSlots == before.freeBenchSlots - 1 && after.owned == expectedOwned
    }
}
