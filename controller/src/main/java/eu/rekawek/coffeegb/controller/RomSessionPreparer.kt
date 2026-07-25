package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootState
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties.Mapper
import eu.rekawek.coffeegb.core.memory.cart.CartridgeType
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException

internal fun interface SessionPreparer {
  fun prepare(properties: EmulatorProperties, event: LoadRomEvent): PreparedSession
}

/** Performs the CPU-heavy BIOS handoff away from the real-time controller thread. */
internal class RomSessionPreparer(
    internal val bootStateCache: BootStateCache = BootStateCache(),
) : SessionPreparer {

  override fun prepare(properties: EmulatorProperties, event: LoadRomEvent): PreparedSession {
    ensureActive()
    val config =
        Controller.createGameboyConfig(properties, Rom(event.rom))
            .setBootCancellation { Thread.currentThread().isInterrupted }
    ensureActive()

    event.state?.let { return PreparedSession.FromDetachedState(config, it) }

    bootStateCache.getOrCreate(config)?.let {
      return PreparedSession.FromBootState(config, it)
    }

    val gameboy = config.build()
    if (Thread.currentThread().isInterrupted) {
      gameboy.discardUnstarted()
      throw CancellationException("ROM preparation superseded")
    }
    return PreparedSession.Ready(config, gameboy)
  }

  private fun ensureActive() {
    if (Thread.currentThread().isInterrupted) {
      throw CancellationException("ROM preparation superseded")
    }
  }
}

/**
 * Small process-local LRU of exact BIOS handoff states. Cache entries contain no file-backed
 * battery data and are restored without replacing the new cartridge's RAM/RTC/mapper state.
 */
internal class BootStateCache(private val capacity: Int = DEFAULT_CAPACITY) {

  private val states =
      object : LinkedHashMap<BootKey, BootState>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BootKey, BootState>?): Boolean =
            size > capacity
      }

  internal var hitCount = 0
    private set

  internal val size: Int
    get() = synchronized(states) { states.size }

  fun getOrCreate(config: GameboyConfiguration): BootState? {
    val key = BootKey.from(config) ?: return null
    synchronized(states) {
      states[key]?.let {
        hitCount++
        return it
      }
    }

    var template: Gameboy? = null
    try {
      template = config.forBootTemplate().build()
      if (Thread.currentThread().isInterrupted) {
        throw CancellationException("ROM preparation superseded")
      }
      val state = template.saveBootState()
      synchronized(states) {
        // Another preparation is not expected for a BasicController's single loader, but keep
        // the cache correct if it is reused by a different caller.
        states[key]?.let {
          hitCount++
          return it
        }
        states[key] = state
      }
      return state
    } finally {
      template?.discardUnstarted()
    }
  }

  private data class BootKey(
      val romDigest: String,
      val profileId: String,
      val displaySgbBorder: Boolean,
      val mealybugDmgBlob: Boolean,
      val codeBreakerRumble: Boolean,
  ) {
    companion object {
      fun from(config: GameboyConfiguration): BootKey? {
        val rom = config.rom
        if (config.bootstrapMode != BootstrapMode.FAST_FORWARD ||
            config.slotRom != null ||
            rom.cartridgeProperties.mapper != Mapper.STANDARD ||
            !isOrdinaryNonRtcCartridge(rom.type)) {
          return null
        }
        return BootKey(
            digest(rom),
            config.hardwareProfile.id(),
            config.isDisplaySgbBorder,
            config.isMealybugDmgBlob,
            config.isCodeBreakerRumble,
        )
      }

      private fun isOrdinaryNonRtcCartridge(type: CartridgeType): Boolean =
          type == CartridgeType.ROM ||
              type == CartridgeType.ROM_RAM ||
              type == CartridgeType.ROM_RAM_BATTERY ||
              type.isMbc1 ||
              type.isMbc2 ||
              type.isMbc5

      private fun digest(rom: Rom): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in rom.rom) {
          digest.update(value.toByte())
        }
        return Base64.getEncoder().encodeToString(digest.digest())
      }
    }
  }

  private companion object {
    const val DEFAULT_CAPACITY = 8
  }
}

internal sealed class PreparedSession(open val config: GameboyConfiguration) {

  abstract fun materialize(): Gameboy

  open fun discard() {}

  data class FromBootState(
      override val config: GameboyConfiguration,
      val bootState: BootState,
  ) : PreparedSession(config) {
    override fun materialize(): Gameboy = materializeRestored { it.restoreBootState(bootState) }
  }

  data class FromDetachedState(
      override val config: GameboyConfiguration,
      val state: MachineState,
  ) : PreparedSession(config) {
    override fun materialize(): Gameboy = materializeRestored { DetachedStateAdapter.apply(it, state) }
  }

  data class Ready(
      override val config: GameboyConfiguration,
      val gameboy: Gameboy,
  ) : PreparedSession(config) {
    override fun materialize(): Gameboy = gameboy

    override fun discard() {
      gameboy.discardUnstarted()
    }
  }

  protected fun materializeRestored(restore: (Gameboy) -> Unit): Gameboy {
    val gameboy = config.forRestore().build()
    try {
      restore(gameboy)
      return gameboy
    } catch (error: Throwable) {
      try {
        gameboy.discardUnstarted()
      } catch (cleanupError: Throwable) {
        error.addSuppressed(cleanupError)
      }
      throw error
    }
  }
}
