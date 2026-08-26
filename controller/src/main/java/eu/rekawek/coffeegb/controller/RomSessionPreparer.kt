package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.properties.RuntimeWarmupFlavor
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootState
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.DesktopRomPersistenceStore
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateRomHashes
import eu.rekawek.coffeegb.controller.state.StateStore
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties.Feature
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties.Mapper
import eu.rekawek.coffeegb.core.memory.cart.CartridgeType
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import org.slf4j.LoggerFactory

internal fun interface SessionPreparer {
  fun prepare(properties: EmulatorProperties, event: LoadRomEvent): PreparedSession
}

/** Performs the CPU-heavy BIOS handoff away from the real-time controller thread. */
internal class RomSessionPreparer(
    internal val bootStateCache: BootStateCache = BootStateCache(),
    private val configure: (Gameboy.GameboyConfiguration) -> Unit = {},
    internal val runtimeWarmupCache: RuntimeWarmupCache = RuntimeWarmupCache.shared,
) : SessionPreparer {

  override fun prepare(properties: EmulatorProperties, event: LoadRomEvent): PreparedSession {
    ensureActive()
    val rom = event.image?.let(::Rom) ?: Rom(event.rom)
    val config =
        Controller.createGameboyConfig(properties, rom)
            .also(configure)
            .setBootCancellation { Thread.currentThread().isInterrupted }
    ensureActive()
    val romHashes = StateIdentity.hashes(config)
    ensureActive()
    val hostPersistenceStore = event.persistenceStore
    val persistence =
        (hostPersistenceStore ?: DesktopRomPersistenceStore(properties.applicationSettings.saves))
            .resolve(config, romHashes)
    persistence.applyTo(config)
    // The desktop controller retains its configurable workspace (and its bounded fallbacks).
    // A supplied host store is the only authoritative destination for a pathless source.
    val stateStore = hostPersistenceStore?.let { persistence.stateStore }
    ensureActive()

    event.state?.let {
      return PreparedSession.FromDetachedState(config, it, romHashes, stateStore)
    }

    // Runtime warmup is a desktop/JIT optimization. Host frontends may explicitly opt out when
    // the disposable 120-frame run would delay first presentation; null retains the desktop
    // default and avoids making this policy persistent.
    if (properties.overrides.runtimeWarmupEnabled != false) {
      val warmed = warmRuntime(
          config,
          properties.overrides.benchmarkPolicyEnabled,
          properties.overrides.runtimeWarmupFlavor,
      )
      if (properties.overrides.benchmarkPolicyEnabled && !warmed) {
        throw IllegalStateException("Benchmark runtime warmup was unavailable")
      }
    } else if (properties.overrides.benchmarkPolicyEnabled) {
      throw IllegalStateException("Benchmark runtime warmup is disabled")
    }

    bootStateCache.getOrCreate(config)?.let {
      return PreparedSession.FromBootState(config, it, romHashes, stateStore)
    }

    // Exotic/RTC cartridges cannot use a battery-free boot template. Defer their real machine
    // construction until after the outgoing session's persistence barrier, when the worker can
    // load the just-committed RAM/RTC bytes without touching the controller timing thread.
    return PreparedSession.Deferred(config, romHashes, stateStore)
  }

  private fun ensureActive() {
    if (Thread.currentThread().isInterrupted) {
      throw CancellationException("ROM preparation superseded")
    }
  }

  /**
   * Warms ordinary post-boot game code before the candidate reaches the controller thread.
   * This is intentionally best-effort: a failed disposable machine must never reject a playable
   * ROM. Cancellation remains observable because a superseded load must not continue preparing.
   */
  private fun warmRuntime(
      config: GameboyConfiguration,
      benchmarkPolicy: Boolean,
      flavor: RuntimeWarmupFlavor,
  ): Boolean {
    try {
      return runtimeWarmupCache.warm(config, flavor, ::ensureActive)
    } catch (error: CancellationException) {
      throw error
    } catch (error: VirtualMachineError) {
      throw error
    } catch (error: ThreadDeath) {
      throw error
    } catch (error: Throwable) {
      ensureActive()
      if (benchmarkPolicy) {
        // A benchmark run must prove that its requested warmup completed.  Ordinary launches
        // retain the best-effort JIT warmup behavior, but silently continuing here would let the
        // diagnostics stream claim ANCHOR_READY for a session that never warmed successfully.
        throw IllegalStateException("Benchmark runtime warmup failed", error)
      }
      LOG.warn(
          "Disposable runtime warmup failed ({}); continuing with the requested ROM load",
          error.javaClass.simpleName,
      )
      return false
    }
  }

  private companion object {
    private val LOG = LoggerFactory.getLogger(RomSessionPreparer::class.java)
  }
}

/** Invokes a disposable, service-free machine without making tests execute millions of ticks. */
internal fun interface RuntimeWarmupExecutor {
  fun warm(config: GameboyConfiguration, ticks: Int, ensureActive: () -> Unit)

  fun warm(
      config: GameboyConfiguration,
      ticks: Int,
      flavor: RuntimeWarmupFlavor,
      ensureActive: () -> Unit,
  ) = warm(config, ticks, ensureActive)
}

/**
 * Process-local bounded cache of JIT warmups, keyed by execution shape rather than ROM identity.
 * HotSpot profiles code globally, so title bytes do not warrant another eight-million-tick run;
 * mapper/type, profile, and every cartridge feature that can alter an execution branch do.
 */
internal class RuntimeWarmupCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val executor: RuntimeWarmupExecutor = GameboyRuntimeWarmupExecutor,
) {

  private val monitor = Object()
  private val completed =
      object : LinkedHashMap<RuntimeWarmupKey, Unit>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<RuntimeWarmupKey, Unit>?
        ): Boolean = size > capacity
      }
  private val inProgress = mutableSetOf<RuntimeWarmupKey>()

  init {
    require(capacity > 0) { "Runtime warmup cache capacity must be positive" }
  }

  internal var hitCount = 0
    private set

  internal val size: Int
    get() = synchronized(monitor) { completed.size }

  /**
   * Performs at most one successful warmup per shape. Waiters observe a completed owner run or
   * take ownership after its cancellation/failure; only successful, still-active runs are cached.
   */
  fun warm(config: GameboyConfiguration, ensureActive: () -> Unit): Boolean =
      warm(config, RuntimeWarmupFlavor.SCALAR, ensureActive)

  fun warm(
      config: GameboyConfiguration,
      flavor: RuntimeWarmupFlavor,
      ensureActive: () -> Unit,
  ): Boolean {
    val key = RuntimeWarmupKey.from(config, flavor) ?: return false
    ensureActive()
    synchronized(monitor) {
      while (true) {
        if (completed[key] != null) {
          hitCount++
          return true
        }
        if (inProgress.add(key)) {
          break
        }
        try {
          monitor.wait()
        } catch (interrupted: InterruptedException) {
          Thread.currentThread().interrupt()
          throw CancellationException("ROM preparation superseded")
        }
        ensureActive()
      }
    }

    try {
      val warmupConfig = config.forRuntimeWarmup().setPlayerInputSource(PlayerInputHub())
      val ticks = Math.multiplyExact(WARMUP_FRAMES, warmupConfig.clockSpec.controllerTicksPerFrame())
      require(ticks > 0) { "Runtime warmup must contain positive controller ticks" }
      executor.warm(warmupConfig, ticks, flavor, ensureActive)
      ensureActive()
      synchronized(monitor) {
        completed[key] = Unit
      }
      return true
    } finally {
      synchronized(monitor) {
        inProgress.remove(key)
        monitor.notifyAll()
      }
    }
  }

  private data class RuntimeWarmupKey(
      val executionMode: eu.rekawek.coffeegb.core.ExecutionMode,
      val flavor: RuntimeWarmupFlavor,
      val profileId: String,
      val mapper: Mapper,
      val cartridgeType: CartridgeType,
      val gameboyColorFlag: Rom.GameboyColorFlag,
      val romLength: Int,
      val ramSize: Int,
      val cartridgeFeatures: List<Feature>,
      val displaySgbBorder: Boolean,
      val mealybugDmgBlob: Boolean,
      val codeBreakerRumble: Boolean,
  ) {
    companion object {
      fun from(
          config: GameboyConfiguration,
          flavor: RuntimeWarmupFlavor,
      ): RuntimeWarmupKey? {
        val rom = config.rom
        // Warmup always runs a disposable post-boot machine.  The requested session may use
        // SKIP, FAST_FORWARD, or NORMAL bootstrap, but replaying an authentic boot here would
        // both waste the preparation worker's budget and contaminate the execution-shape cache
        // with a boot policy that is not part of the warmed game-code path.
        if (config.slotRom != null ||
            rom.cartridgeProperties.mapper != Mapper.STANDARD ||
            !isOrdinaryNonRtcCartridge(rom.type)) {
          return null
        }
        if (flavor == RuntimeWarmupFlavor.SHADOW_MEASURED_EXACT_V1
            && (config.executionMode != eu.rekawek.coffeegb.core.ExecutionMode.PERFORMANCE
                || config.hardwareProfile.id() != "cgb"
                || rom.gameboyColorFlag == Rom.GameboyColorFlag.NON_CGB)) {
          return null
        }
        val cartridgeFeatures = Feature.values().filter(rom.cartridgeProperties::has)
        return RuntimeWarmupKey(
            config.executionMode,
            flavor,
            config.hardwareProfile.id(),
            rom.cartridgeProperties.mapper,
            rom.type,
            rom.gameboyColorFlag,
            rom.rom.size,
            rom.ramSize,
            cartridgeFeatures,
            config.isDisplaySgbBorder,
            config.isMealybugDmgBlob,
            config.isCodeBreakerRumble,
        )
      }
    }
  }

  internal object GameboyRuntimeWarmupExecutor : RuntimeWarmupExecutor {
    override fun warm(config: GameboyConfiguration, ticks: Int, ensureActive: () -> Unit) {
      warm(config, ticks, RuntimeWarmupFlavor.SCALAR, ensureActive)
    }

    override fun warm(
        config: GameboyConfiguration,
        ticks: Int,
        flavor: RuntimeWarmupFlavor,
        ensureActive: () -> Unit,
    ) {
      val delegate = EventBusImpl(null, "runtime-warmup", false)
      val eventBus = StagedEventBus(delegate)
      var gameboy: Gameboy? = null
      try {
        gameboy = config.build()
        gameboy.init(
            eventBus,
            Peer2PeerSerialEndpoint(),
            InfraredEndpoint.NULL_ENDPOINT,
            null,
        )
        // Match the live Session receiver shape without attaching any UI/audio subscribers.
        eventBus.activate()
        if (flavor == RuntimeWarmupFlavor.SHADOW_MEASURED_EXACT_V1) {
          val frameTicks = config.clockSpec.controllerTicksPerFrame()
          require(frameTicks > 0) { "Runtime warmup frame must contain positive controller ticks" }
          val gate = BenchmarkCoreFrozenGate()
          gameboy.sound.setPerformanceSystemMutedAudioMode(
              eu.rekawek.coffeegb.core.sound.Sound.PerformanceSystemMutedAudioMode.EXACT)
          gameboy.resetPerformanceBulkCounters()
          gameboy.sound.resetPerformanceSystemMutedAudioCalendarCounters()
          var fullTicks = 0L
          repeat(WARMUP_FRAMES) { frame ->
            if (frame % CANCELLATION_CHECK_FRAMES == 0) {
              ensureActive()
            }
            val executed = gameboy.runMeasuredTicksUntilStop(frameTicks, gate)
            require(executed == frameTicks) {
              "Shadow measured warmup stopped after $executed/$frameTicks ticks"
            }
            fullTicks += executed.toLong()
          }
          ensureActive()
          val totalTicks = Math.multiplyExact(WARMUP_FRAMES.toLong(), frameTicks.toLong())
          require(ticks.toLong() == totalTicks) {
            "Shadow measured warmup received an unexpected tick budget"
          }
          require(fullTicks == totalTicks) {
            "Shadow measured warmup did not execute its full tick budget"
          }
          val sound = gameboy.sound
          require(sound.getPerformanceSystemMutedAudioCalendarSkippedTicks() == totalTicks) {
            "Shadow measured warmup did not account every tick in EXACT mode"
          }
          require(sound.getPerformanceSystemMutedAudioCalendarZeroSampleSlots() > 0L) {
            "Shadow measured warmup emitted no silent PCM slots"
          }
          require(sound.getPerformanceSystemMutedAudioCalendarDroppedChannelTicks() == 0L) {
            "Shadow measured warmup dropped channel ticks"
          }
          require(sound.getPerformanceSystemMutedAudioCalendarZeroSampleEvents() > 0L) {
            "Shadow measured warmup emitted no silent PCM events"
          }
          require(sound.getPerformanceSystemMutedAudioCalendarMaxPendingTicks() > 0L) {
            "Shadow measured warmup did not accumulate an EXACT span"
          }
          require(sound.getPerformanceSystemMutedAudioCalendarFrameSequencerCommits() > 0L) {
            "Shadow measured warmup did not commit the frame sequencer"
          }
          require(gameboy.getPerformanceEpochCount() > 0L
              && gameboy.getPerformanceEpochTicks() > 0L
              && gameboy.getPerformanceEpochMaxTicks() <= MAX_NATIVE_EPOCH_TICKS) {
            "Shadow measured warmup did not exercise bounded native epochs"
          }
        } else {
          repeat(ticks) { tick ->
            if (tick % CANCELLATION_CHECK_TICKS == 0) {
              ensureActive()
            }
            gameboy.tick()
          }
        }
        ensureActive()
      } finally {
        try {
          gameboy?.let {
            try {
              it.sound.setPerformanceSystemMutedAudioMode(
                  eu.rekawek.coffeegb.core.sound.Sound.PerformanceSystemMutedAudioMode.OFF)
            } finally {
              it.discardUnstarted()
            }
          }
        } finally {
          eventBus.close()
        }
      }
    }
  }

  companion object {
    const val WARMUP_FRAMES = 120
    const val CANCELLATION_CHECK_TICKS = 4_096
    const val CANCELLATION_CHECK_FRAMES = 8
    const val MAX_NATIVE_EPOCH_TICKS = 54
    const val DEFAULT_CAPACITY = 8

    internal val shared = RuntimeWarmupCache()
  }
}

private fun isOrdinaryNonRtcCartridge(type: CartridgeType): Boolean =
    type == CartridgeType.ROM ||
        type == CartridgeType.ROM_RAM ||
        type == CartridgeType.ROM_RAM_BATTERY ||
        type.isMbc1 ||
        type.isMbc2 ||
        type.isMbc5

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
      val executionMode: eu.rekawek.coffeegb.core.ExecutionMode,
      val bootstrapMode: BootstrapMode,
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
            config.executionMode,
            config.bootstrapMode,
            config.isDisplaySgbBorder,
            config.isMealybugDmgBlob,
            config.isCodeBreakerRumble,
        )
      }

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

internal sealed class PreparedSession(
    open val config: GameboyConfiguration,
    open val romHashes: StateRomHashes,
    /** Host-owned state destination resolved with the session's battery storage. */
    open val stateStore: StateStore? = null,
) {

  abstract fun materialize(): Gameboy

  open fun discard() {}

  data class FromBootState(
      override val config: GameboyConfiguration,
      val bootState: BootState,
      override val romHashes: StateRomHashes = StateIdentity.hashes(config),
      override val stateStore: StateStore? = null,
  ) : PreparedSession(config, romHashes, stateStore) {
    override fun materialize(): Gameboy = materializeRestored { it.restoreBootState(bootState) }
  }

  data class FromDetachedState(
      override val config: GameboyConfiguration,
      val state: MachineState,
      override val romHashes: StateRomHashes = StateIdentity.hashes(config),
      override val stateStore: StateStore? = null,
  ) : PreparedSession(config, romHashes, stateStore) {
    override fun materialize(): Gameboy = materializeRestored { DetachedStateAdapter.apply(it, state) }
  }

  data class Deferred(
      override val config: GameboyConfiguration,
      override val romHashes: StateRomHashes = StateIdentity.hashes(config),
      override val stateStore: StateStore? = null,
  ) : PreparedSession(config, romHashes, stateStore) {
    override fun materialize(): Gameboy = config.build()
  }

  class Ready(
      override val config: GameboyConfiguration,
      gameboy: Gameboy,
      override val romHashes: StateRomHashes = StateIdentity.hashes(config),
      override val stateStore: StateStore? = null,
  ) : PreparedSession(config, romHashes, stateStore) {
    private val owned = java.util.concurrent.atomic.AtomicReference(gameboy)

    override fun materialize(): Gameboy =
        owned.getAndSet(null) ?: throw CancellationException("Prepared machine already transferred")

    override fun discard() {
      owned.getAndSet(null)?.discardUnstarted()
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
