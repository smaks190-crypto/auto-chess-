package dev.nox.local

/** Worker-confined native resource. Hibernation retains the exact successful recipe;
 * neither memory pressure nor a failed reload selects another model/backend. */
internal class ResidentModel<C : Any, R : Any>(
    private val create: (C) -> R,
    private val release: (R) -> Unit
) : AutoCloseable {
    private var recipe: C? = null
    private var resource: R? = null
    val resident: Boolean get() = resource != null

    fun load(config: C) {
        close()
        val fresh = create(config)
        resource = fresh
        recipe = config
    }

    fun requireResident(): R {
        resource?.let { return it }
        val config = checkNotNull(recipe) { "Сначала загрузите модель" }
        return create(config).also { resource = it }
    }

    fun hibernate(): Boolean {
        val old = resource ?: return false
        resource = null
        try {
            release(old)
        } catch (error: Throwable) {
            // Native destruction is uncertain. Do not create a second engine on top.
            recipe = null
            throw error
        }
        return true
    }

    override fun close() {
        recipe = null
        hibernate()
    }
}
