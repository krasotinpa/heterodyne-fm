package fm.heterodyne.app

/** The caller serializes this lifecycle with USB operations. */
class DiagnosticExport(private val disconnect: () -> Unit, private val retry: () -> Unit) {
    var active = false
        private set
    fun begin() {
        check(!active)
        active = true
        try { disconnect() } catch (failure: Exception) { active = false; throw failure }
    }
    fun finish(write: (() -> Unit)? = null): String? {
        if (!active) return if (write == null) null else "Экспорт прерван. Повторите экспорт Журнала"
        return try { write?.invoke(); null }
        catch (_: Exception) { "Не удалось записать Журнал" }
        finally { active = false; retry() }
    }
}
