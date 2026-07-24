package eu.rekawek.coffeegb.controller

/**
 * Audited concrete types present in Coffee GB 1.7.14 legacy snapshots.
 *
 * IDs are the one-based position in each list for protocol v7 only. The legacy reader and the
 * temporary bounded netplay codec deliberately share this exact inventory so a future record or
 * enum cannot silently become deserializable. The persistent State v2 schema will use its own
 * section identities and versions.
 */
internal object MementoTypeRegistry {

  val recordClassNames =
      listOf(
          "eu.rekawek.coffeegb.core.genie.Genie\$GenieMemento",
          "eu.rekawek.coffeegb.core.sound.FrameSequencer\$FrameSequencerMemento",
          "eu.rekawek.coffeegb.core.sound.FrequencySweep\$FrequencySweepMemento",
          "eu.rekawek.coffeegb.core.sound.AbstractSoundMode\$AbstractSoundModeMemento",
          "eu.rekawek.coffeegb.core.sound.Lfsr\$LfsrMemento",
          "eu.rekawek.coffeegb.core.sound.PolynomialCounter\$PolynomialCounterMemento",
          "eu.rekawek.coffeegb.core.sound.VolumeEnvelope\$VolumeEnvelopeMemento",
          "eu.rekawek.coffeegb.core.sound.SoundMode4\$SoundMode4Memento",
          "eu.rekawek.coffeegb.core.sound.SoundMode3\$SoundMode3Memento",
          "eu.rekawek.coffeegb.core.sound.SoundMode2\$SoundMode2Memento",
          "eu.rekawek.coffeegb.core.sound.SoundMode1\$SoundMode1Memento",
          "eu.rekawek.coffeegb.core.sound.LengthCounter\$LengthCounterMemento",
          "eu.rekawek.coffeegb.core.sound.Sound\$SoundMemento",
          "eu.rekawek.coffeegb.core.timer.Timer\$TimerMemento",
          "eu.rekawek.coffeegb.core.cpu.SpeedMode\$SpeedModeMomento",
          "eu.rekawek.coffeegb.core.cpu.InterruptManager\$InterruptManagerMemento",
          "eu.rekawek.coffeegb.core.cpu.Registers\$RegistersMemento",
          "eu.rekawek.coffeegb.core.cpu.Cpu\$CpuMemento",
          "eu.rekawek.coffeegb.core.joypad.Joypad\$JoypadMemento",
          "eu.rekawek.coffeegb.core.sgb.Commands\$TransferCommand\$TransferCommandMemento",
          "eu.rekawek.coffeegb.core.sgb.SuperGameboy\$SuperGameboyMemento",
          "eu.rekawek.coffeegb.core.sgb.SgbDisplay\$SgbDisplayMemento",
          "eu.rekawek.coffeegb.core.sgb.Background\$BackgroundMemento",
          "eu.rekawek.coffeegb.core.memory.Hdma\$HdmaMemento",
          "eu.rekawek.coffeegb.core.memory.GbcRam\$GbcRamMemento",
          "eu.rekawek.coffeegb.core.memory.BiosShadow\$BiosShadowMemento",
          "eu.rekawek.coffeegb.core.memory.Ram\$RamMemento",
          "eu.rekawek.coffeegb.core.memory.Mmu\$MmuMemento",
          "eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery\$MemoryBatteryMemento",
          "eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery\$FileBatteryMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc1\$Mbc1Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.BungEms\$BungEmsMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc2\$Mbc2Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$EepromMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.BasicRom\$BasicRomMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc5\$Mbc5Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera\$PocketCameraMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.BhgosMulticart\$BhgosMulticartMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7\$Mbc7Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.MakonNtOld2\$MakonNtOld2Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Sintax\$SintaxMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.WisdomTree\$WisdomTreeMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Huc3\$Huc3Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.DuzMulticart\$DuzMulticartMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mani32kMulticart\$Mani32kMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc6\$Mbc6Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Huc1\$Huc1Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc\$SachenMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc3\$Mbc3Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Bbd\$BbdMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart\$SlMulticartMemento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mmm01\$Mmm01Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Tama5\$Tama5Memento",
          "eu.rekawek.coffeegb.core.memory.cart.type.Datel\$DatelMemento",
          "eu.rekawek.coffeegb.core.memory.cart.Cartridge\$CartridgeMemento",
          "eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock\$RealTimeClockMemento",
          "eu.rekawek.coffeegb.core.memory.UndocumentedGbcRegisters\$UndocumentedGbcRegistersMemento",
          "eu.rekawek.coffeegb.core.memory.Dma\$DmaMemento",
          "eu.rekawek.coffeegb.core.memory.OamEchoRam\$OamEchoRamMemento",
          "eu.rekawek.coffeegb.core.Gameboy\$GameboyMemento",
          "eu.rekawek.coffeegb.core.gpu.IntQueue\$IntQueueMemento",
          "eu.rekawek.coffeegb.core.gpu.GpuRegisterValues\$GpuRegisterValuesMemento",
          "eu.rekawek.coffeegb.core.gpu.Lcdc\$LcdcMemento",
          "eu.rekawek.coffeegb.core.gpu.Display\$DisplayMemento",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$GpuMemento",
          "eu.rekawek.coffeegb.core.gpu.StatRegister\$StatRegisterMemento",
          "eu.rekawek.coffeegb.core.gpu.VRamTransfer\$VRamTransferMemento",
          "eu.rekawek.coffeegb.core.gpu.SpriteFifo\$SpriteFifoMemento",
          "eu.rekawek.coffeegb.core.gpu.ColorPalette\$ColorPaletteMemento",
          "eu.rekawek.coffeegb.core.gpu.DmgPixelFifo\$DmgPixelFifoMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.HBlankPhase\$HBlankPhaseMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$PixelTransferMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$SpritePosition\$SpritePositionMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.VBlankPhase\$VBlankPhaseMemento",
          "eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$OamSearchMemento",
          "eu.rekawek.coffeegb.core.gpu.ColorPixelFifo\$ColorPixelFifoMemento",
          "eu.rekawek.coffeegb.core.gpu.Fetcher\$FetcherMemento",
          "eu.rekawek.coffeegb.core.ir.FullChanger\$FullChangerMemento",
          "eu.rekawek.coffeegb.core.ir.InfraredPort\$InfraredPortMemento",
          "eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint\$Peer2PeerSerialEndpointMemento",
          "eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint\$PrinterMemento",
          "eu.rekawek.coffeegb.core.serial.SerialPort\$SerialPortMemento",
          "eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint\$GpsReceiverMemento",
          "eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint\$ByteReceivingSerialEndpointMemento",
          "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$BarcodeBoyMemento",
          "eu.rekawek.coffeegb.core.serial.FourPlayerAdapter\$AdapterMemento",
          "eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble\$CodeBreakerRumbleMemento",
          "eu.rekawek.coffeegb.core.genie.GameGeniePatch",
          "eu.rekawek.coffeegb.core.genie.GameSharkPatch",
          "eu.rekawek.coffeegb.core.gpu.Gpu\$PendingPpuWrite",
          "eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer\$DelayedWindowWrite",
      )

  val enumClassNames =
      listOf(
          "eu.rekawek.coffeegb.core.cpu.Cpu\$State",
          "eu.rekawek.coffeegb.core.cpu.InterruptManager\$InterruptType",
          "eu.rekawek.coffeegb.core.gpu.Mode",
          "eu.rekawek.coffeegb.core.gpu.phase.OamSearch\$State",
          "eu.rekawek.coffeegb.core.memory.Hdma\$CpuRequestArbitration",
          "eu.rekawek.coffeegb.core.memory.Hdma\$HaltHdmaState",
          "eu.rekawek.coffeegb.core.memory.Hdma\$WakeRequestArbitration",
          "eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom\$State",
          "eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint\$State",
          "eu.rekawek.coffeegb.core.serial.FourPlayerAdapter\$Phase",
          "eu.rekawek.coffeegb.core.sgb.Commands\$MaskEnCmd\$GameboyScreenMask",
      )

  val legacyApplicationClassNames: Set<String> =
      (recordClassNames + enumClassNames).toSet()

  val recordClasses: List<Class<*>> by lazy {
    recordClassNames.map(::loadAuditedClass).also { classes ->
      classes.forEach { require(it.isRecord) { "Audited portable type is no longer a record: $it" } }
    }
  }

  val enumClasses: List<Class<*>> by lazy {
    enumClassNames.map(::loadAuditedClass).also { classes ->
      classes.forEach { require(it.isEnum) { "Audited portable type is no longer an enum: $it" } }
    }
  }

  private fun loadAuditedClass(name: String): Class<*> = Class.forName(name, false, javaClass.classLoader)
}
