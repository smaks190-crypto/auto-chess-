package dev.nox.local.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationAdvisorTest {
    private val priority = listOf("Scout", "Guard")
    private val guard = ShopOffer("Guard", 2)
    private val combat = MatchFrame(
        round = 2, sequence = 10, phase = MatchPhase.COMBAT,
        gold = 3, freeBenchSlots = 1, shop = listOf(ShopOffer("Scout", 4), guard),
        owned = emptyMap()
    )
    private val prep = combat.copy(sequence = 11, phase = MatchPhase.PREPARATION)
    private val advice = PurchaseAdvice(1, guard)
    private val purchased = prep.copy(
        sequence = 12, gold = 1, freeBenchSlots = 0,
        shop = listOf(prep.shop[0], null), owned = mapOf("Guard" to 1)
    )

    @Test fun replayFromCombatThroughPurchase() {
        assertNull(PreparationAdvisor.recommend(combat, priority))
        assertEquals(advice, PreparationAdvisor.recommend(prep, priority))
        assertTrue(PreparationAdvisor.observedPurchase(prep, advice, purchased))
        assertNull(PreparationAdvisor.recommend(purchased, priority))
    }

    @Test fun unavailableActionsHaveNoRecommendation() {
        assertNull(PreparationAdvisor.recommend(prep.copy(phase = MatchPhase.UNKNOWN), priority))
        assertNull(PreparationAdvisor.recommend(prep.copy(gold = 0), priority))
        assertNull(PreparationAdvisor.recommend(prep.copy(freeBenchSlots = 0), priority))
        assertNull(PreparationAdvisor.recommend(prep, listOf("Mage")))
    }

    @Test fun staleOrIncompleteEffectsCannotConfirmAPurchase() {
        val contradictoryFrames = listOf(
            purchased.copy(round = 3),
            purchased.copy(sequence = prep.sequence),
            purchased.copy(sequence = prep.sequence + 2),
            purchased.copy(phase = MatchPhase.COMBAT),
            purchased.copy(gold = prep.gold),
            purchased.copy(freeBenchSlots = prep.freeBenchSlots),
            purchased.copy(shop = prep.shop),
            purchased.copy(shop = listOf(null, null)),
            purchased.copy(owned = prep.owned)
        )
        for (frame in contradictoryFrames) {
            assertFalse(PreparationAdvisor.observedPurchase(prep, advice, frame))
        }
        assertFalse(PreparationAdvisor.observedPurchase(combat, advice, purchased))
        assertFalse(PreparationAdvisor.observedPurchase(prep, advice.copy(slot = -1), purchased))
    }
}
