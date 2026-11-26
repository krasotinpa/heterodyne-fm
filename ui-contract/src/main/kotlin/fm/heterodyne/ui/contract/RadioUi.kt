package fm.heterodyne.ui.contract

import kotlinx.coroutines.flow.StateFlow

/** The app or Sandbox owns the session. Reading state never starts work. */
interface RadioUi {
    /** Latest coherent snapshot. Publishers own immutable values and collections. */
    val state: StateFlow<RadioUiState>

    /** Returns admission to the operation, independently of its eventual outcome. */
    suspend fun dispatch(action: RadioAction): Admission
}

sealed interface Admission {
    data object Accepted : Admission
    data class Rejected(val reason: RejectReason) : Admission
}

enum class RejectReason {
    WrongMode, NotForeground, NotConnected, Unsupported, Busy, RateLimited,
    InvalidInput, StaleContext, IncompatibleState, TransportError
}
data class ActionIssue(val control: Control, val reason: RejectReason)
