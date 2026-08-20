package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource
import eu.rekawek.coffeegb.core.serial.SerialEndpoint

/** Builds a service-isolated configuration without copying battery bytes or host backends. */
internal object ReplayRuntime {
  fun configuration(
      source: Gameboy.GameboyConfiguration,
      rtcTimeSource: TimeSource,
      playerInputSource: PlayerInputSource,
      executionMode: ExecutionMode = source.executionMode,
  ): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(source.rom)
          .setHardwareProfile(source.hardwareProfile)
          .setBootstrapMode(source.bootstrapMode)
          .setSlotRom(source.slotRom)
          .setMealybugDmgBlob(source.isMealybugDmgBlob)
          .setCodeBreakerRumble(source.isCodeBreakerRumble)
          .setDisplaySgbBorder(source.isDisplaySgbBorder)
          .setExecutionMode(executionMode)
          .setSupportBatterySave(false)
          .setBatteryStorage(null, null)
          .setRtcTimeSource(rtcTimeSource)
          .setPlayerInputSource(playerInputSource)

  fun session(
      configuration: Gameboy.GameboyConfiguration,
      restoreImmediately: Boolean,
      serialEndpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT,
  ): Session {
    val prebuilt = if (restoreImmediately) configuration.forRestore().build() else null
    return Session(
        configuration,
        EventBusImpl(null, null, false),
        null,
        serialEndpoint = serialEndpoint,
        prebuiltGameboy = prebuilt,
    )
  }
}
