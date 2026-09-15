package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.ir.GbfFile
import eu.rekawek.coffeegb.core.ir.GbKissLink

/** The file is immutable and validated before entering the emulation-owner queue. */
data class GbKissTransferRequest(val requestId: Long, val file: GbfFile? = null) : Event
data class GbKissCancelRequest(val requestId: Long) : Event
data class GbKissProgressEvent(val requestId: Long, val progress: GbKissLink.Progress) : Event
data class GbKissReceivedEvent(val requestId: Long, val file: GbfFile) : Event
