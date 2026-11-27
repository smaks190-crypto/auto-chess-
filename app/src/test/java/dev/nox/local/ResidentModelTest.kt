package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class ResidentModelTest {
    data class Recipe(val file: String, val gpu: Boolean, val vision: Boolean)
    class Resource(val recipe: Recipe) { var closed = false }

    @Test fun hibernationReloadsSameRecipe() {
        val configs = mutableListOf<Recipe>()
        val recipe = Recipe("gemma", true, true)
        val model = ResidentModel<Recipe, Resource>({ configs.add(it); Resource(it) }, { check(!it.closed); it.closed = true })
        model.load(recipe)
        val old = model.requireResident()
        assertSame(old, model.requireResident())
        assertTrue(model.hibernate()); assertTrue(old.closed)
        assertFalse(model.resident); assertFalse(model.hibernate())
        val next = model.requireResident()
        assertNotSame(old, next)
        assertEquals(listOf(recipe, recipe), configs)
        model.close(); assertTrue(next.closed)
    }

    @Test fun explicitCloseClearsRecipeAndDoesNotReload() {
        var creates = 0
        val model = ResidentModel<String, String>({ creates++; it }, {})
        model.load("a"); model.close()
        try { model.requireResident(); fail("must reject") } catch (_: IllegalStateException) {}
        assertEquals(1, creates)
    }

    @Test fun failedReloadRetainsExactRecipeForLaterExplicitRequest() {
        var fail = false
        val model = ResidentModel<String, String>({ check(!fail); it }, {})
        model.load("gemma"); model.hibernate()
        fail = true
        try { model.requireResident(); fail("must reject") } catch (_: IllegalStateException) {}
        assertFalse(model.resident)
        fail = false
        assertEquals("gemma", model.requireResident())
    }

    @Test fun replacingRecipeClosesOldResource() {
        val old = Resource(Recipe("a", true, true))
        val replacement = Resource(Recipe("b", false, false))
        val model = ResidentModel<Recipe, Resource>({ if (it == old.recipe) old else replacement }, { it.closed = true })
        model.load(old.recipe); model.load(replacement.recipe)
        assertTrue(old.closed); assertSame(replacement, model.requireResident())
    }

    @Test fun failedNativeReleaseDisablesAutomaticReload() {
        var creates = 0
        val model = ResidentModel<String, String>({ creates++; it }, { error("close failed") })
        model.load("a")
        try { model.hibernate(); fail("must reject") } catch (_: IllegalStateException) {}
        try { model.requireResident(); fail("must reject") } catch (_: IllegalStateException) {}
        assertEquals(1, creates)
    }
}
