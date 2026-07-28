package eu.rekawek.coffeegb.controller.state

import java.util.UUID

/** A machine-state address whose untrusted label is never used as a filesystem component. */
sealed interface StateRef {
  val namespace: StateNamespace

  /** Stable canonical value stored in metadata, not an arbitrary path. */
  fun storageKey(): String

  data class Slot(val index: Int) : StateRef {
    init {
      require(index in MIN_SLOT..MAX_SLOT) { "State slot must be between $MIN_SLOT and $MAX_SLOT" }
    }

    override val namespace = StateNamespace.SLOT

    override fun storageKey(): String = "slot:$index"
  }

  data class Named(val id: UUID) : StateRef {
    override val namespace = StateNamespace.NAMED

    override fun storageKey(): String = "named:$id"
  }

  data object Autosave : StateRef {
    override val namespace = StateNamespace.AUTOSAVE

    override fun storageKey(): String = "autosave"
  }

  companion object {
    const val MIN_SLOT = 0
    const val MAX_SLOT = 9
    const val SLOT_COUNT = MAX_SLOT - MIN_SLOT + 1

    fun parseStorageKey(value: String): StateRef =
        when {
          value == "autosave" -> Autosave
          value.startsWith("slot:") -> {
            val suffix = value.removePrefix("slot:")
            val index = suffix.toIntOrNull()
            require(index != null && suffix == index.toString()) {
              "Invalid state slot reference: $value"
            }
            Slot(index)
          }
          value.startsWith("named:") -> {
            val suffix = value.removePrefix("named:")
            val id =
                try {
                  UUID.fromString(suffix)
                } catch (failure: IllegalArgumentException) {
                  throw IllegalArgumentException("Invalid named-state reference: $value", failure)
                }
            require(suffix == id.toString()) { "Named-state UUID must use canonical lowercase text" }
            Named(id)
          }
          else -> throw IllegalArgumentException("Unknown state reference: $value")
        }
  }
}

enum class StateNamespace {
  SLOT,
  NAMED,
  AUTOSAVE,
}
