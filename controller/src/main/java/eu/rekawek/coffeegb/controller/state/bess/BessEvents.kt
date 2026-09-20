package eu.rekawek.coffeegb.controller.state.bess

import eu.rekawek.coffeegb.core.events.Event
import java.nio.file.Path

data class BessLoadRequestEvent(val path: Path, val expectedSessionId: Long) : Event

data class BessSaveRequestEvent(val path: Path, val expectedSessionId: Long) : Event

data class BessOperationCompletedEvent(val path: Path, val load: Boolean) : Event

data class BessOperationFailedEvent(val message: String) : Event
