# Audited state-record schema

This is the exact captured-field inventory for all 99 production record types admitted by
`StateTypeRegistry`. Each bullet's one-based position is its stable StateFile-v1 record type ID;
each line names the non-serializable `ComponentState` record and every component captured by
`captureState`. The
executable state inventory test prevents the registry, this appendix, and the detached graph
adapter from drifting independently.

Several component labels still end in `Memento`. They are frozen StateFile-v1 field identifiers,
not Java-serialization dependencies, and changing them would change canonical v1 bytes. The local
historical importer has a parallel, ID-aligned registry of the released `*Memento` compatibility
records. Those data-only mirrors are never constructed or invoked by live owners.

IDs 88 through 91 use non-serializable explicit-state leaf records. Their ID-aligned historical
counterparts retain the released `GameGeniePatch`, `GameSharkPatch`, `Gpu.PendingPpuWrite`, and
`PixelTransfer.DelayedWindowWrite` binary names only inside the local importer. Type names are not
encoded by StateFile v1; the identical numeric IDs, field names, order, and primitive values keep
canonical v1 bytes unchanged.

Array and collection fields are deep-owned by the detached state adapter. The ownership contract,
safe points, derived/excluded state, and subsystem grouping are in
[state-machine-inventory.md](state-machine-inventory.md).

Mobile Adapter engine records include the complete private 256-byte adapter configuration. A
capture made while an ISP-login request is only partly parsed can also include bounded ID/password
bytes in `packetBuffer`. State files containing this peripheral must therefore be kept private or
explicitly redacted; sockets, resolver handles, backend tasks, and runtime connection identifiers
remain excluded.

The 11 explicit behavior tags are `Cpu.State`, `InterruptManager.InterruptType`, `Gpu.Mode`,
`OamSearch.State`, `Hdma.CpuRequestArbitration`, `Hdma.HaltHdmaState`,
`Hdma.WakeRequestArbitration`, `Mbc7Eeprom.State`, `BarcodeBoySerialEndpoint.State`,
`FourPlayerAdapter.Phase`, and `Commands.MaskEnCmd.GameboyScreenMask`. Their portable form is the
audited enum type ID plus explicit one-based v1 value ID; the complete value registry is in
[state-file-v1.md](state-file-v1.md). A missing type or out-of-range value ID is rejected.

`DmgPixelFifo$DmgPixelFifoState` has seven components matching its stable v1 record schema. The
separate importer-only `DmgPixelFifoMemento` retains the pinned Java descriptor. The immutable
`MachineState` carries `linePixels`, `outCount`,
`firstEntry`, `firstBgp`, `firstObp0`, and `firstObp1` for both DMG dot machines in the separate
primitive-only `DmgFifoRuntimeState` supplement. This closes current state ownership without
altering the 1.7.13/1.7.14 migration schema listed below.

- `eu.rekawek.coffeegb.core.genie.Genie$GenieState`: patches
- `eu.rekawek.coffeegb.core.sound.FrameSequencer$FrameSequencerState`: step, previousBit, skipNextEdge
- `eu.rekawek.coffeegb.core.sound.FrequencySweep$FrequencySweepState`: period, negate, shift, timer, shadowFreq, nr13, nr14, overflow, counterEnabled, negging, calculationDelay, unshiftedCalculation, restartHold, frequencyUpdatePending
- `eu.rekawek.coffeegb.core.sound.AbstractSoundMode$AbstractSoundModeState`: channelEnabled, dacEnabled, nr0, nr1, nr2, nr3, nr4, lengthMemento
- `eu.rekawek.coffeegb.core.sound.Lfsr$LfsrState`: lfsr
- `eu.rekawek.coffeegb.core.sound.PolynomialCounter$PolynomialCounterState`: nr43, counter, counterCountdown, clock2Mhz, alignment, backgroundActive, countdownReloaded
- `eu.rekawek.coffeegb.core.sound.VolumeEnvelope$VolumeEnvelopeState`: initialVolume, envelopeDirection, sweep, volume, timer, finished, pendingEnvelopeClock
- `eu.rekawek.coffeegb.core.sound.SoundMode4$SoundMode4State`: abstractSoundMemento, volumeEnvelopeMemento, polynomialCounterMemento, lastResult, lfsrMemento
- `eu.rekawek.coffeegb.core.sound.SoundMode3$SoundMode3State`: abstractSoundMemento, waveRamMemento, freqDivider, lastOutput, i, ticksSinceRead, lastReadAddr, buffer, triggered, clock2Mhz
- `eu.rekawek.coffeegb.core.sound.SoundMode2$SoundMode2State`: abstractSoundMemento, freqDivider, lastOutput, i, sampleSuppressed, activeBeforeTrigger, clock2Mhz, lowFrequencyPhase, volumeEnvelopeMemento, justReloadedTicks
- `eu.rekawek.coffeegb.core.sound.SoundMode1$SoundMode1State`: abstractSoundMemento, freqDivider, lastOutput, i, sampleSuppressed, activeBeforeTrigger, clock2Mhz, lowFrequencyPhase, frequencySweepMemento, justReloadedTicks, justReloadedFromSweep, volumeEnvelopeMemento
- `eu.rekawek.coffeegb.core.sound.LengthCounter$LengthCounterState`: length, enabled
- `eu.rekawek.coffeegb.core.sound.Sound$SoundState`: allModeMementos, ramMemento, frameSequencerMemento, channels, enabled, overriddenEnabled, buffer, i, pendingFrameSequencerStep, frameSequencerClockPhase, frameSequencerDivOffset
- `eu.rekawek.coffeegb.core.timer.Timer$TimerState`: div, tac, tma, tima, previousBit, overflow, ticksSinceOverflow, divReset, haltWakeDelay, ticksSinceDivReset, haltBugDivRipplePending, haltBugDivRippleVisible, suppressNextInterruptRequest
- `eu.rekawek.coffeegb.core.cpu.SpeedMode$SpeedModeState`: currentSpeed, prepareSpeedSwitch, dmgCompat
- `eu.rekawek.coffeegb.core.cpu.InterruptManager$InterruptManagerState`: ime, interruptFlag, interruptEnabled, pendingEnableInterrupts, haltBlockedInterrupts, cpuBlockedInterrupts, cpuPhasedPpuInterrupts, cpuPhasedMode2Interrupts, cpuFirstLineMode2Interrupts, cpuInstructionBlockedInterrupts, maskVBlankOnNextRead, maskLcdcUntilNextPeripheralTick, maskMode0LcdcReadTicks, cpuReadInterruptPreview, serialInterruptAcknowledge, timerInterruptAcknowledge, lcdcInterruptAcknowledge, vBlankInterruptAcknowledge, lcdcInterruptFlagWriteClear
- `eu.rekawek.coffeegb.core.cpu.Registers$RegistersState`: a, b, c, d, e, h, l, sp, pc, flags
- `eu.rekawek.coffeegb.core.cpu.Cpu$CpuState`: registersMemento, opcode1, opcode2, operand, operandIndex, opIndex, state, opContext, interruptFlag, interruptEnabled, requestedIrq, clockCycle, haltBugMode, haltEntrySampleTicks, synchronousHaltEntryStatPhase, asynchronousHaltEntryStatPhase, ordinaryHaltWakeStatPhase, haltedCpuCycles, hdmaOpcodePrefetched, hdmaArbitrationOpcode, hdmaArbitrationOpcodeValid, haltPrefetchedOpcode, haltOpcodePrefetchValid, speedSwitchPaddingOpcode, speedSwitchPaddingReplayValid, speedSwitchTicks, phasedPpuInputHigh, fastPhasedPpuDispatch
- `eu.rekawek.coffeegb.core.joypad.Joypad$JoypadState`: p1, tick, inputHistory, filteredInputLines, inputChangedSinceLastTick, players, currentPlayer, transferInProgress, transferReadyForData, pendingTransferBit, currentByte, currentPacket, currentByteIndex, currentPacketIndex
- `eu.rekawek.coffeegb.core.sgb.Commands$TransferCommand$TransferCommandState`: packet, dataTransfer
- `eu.rekawek.coffeegb.core.sgb.SuperGameboy$SuperGameboyState`: multipacket, multipacketIndex, multipacketLength, transferCountdown, waitingTransferCommandMemento
- `eu.rekawek.coffeegb.core.sgb.SgbDisplay$SgbDisplayState`: sgbBuffer, sgbMask, palettes, systemPalettes, paletteMap, attributeFiles, screenMask, borderFade
- `eu.rekawek.coffeegb.core.sgb.Background$BackgroundState`: tiles, pendingPictureMemento, borderAnimation
- `eu.rekawek.coffeegb.core.memory.Hdma$HdmaState`: gpuMode, transferInProgress, hblankTransfer, lcdEnabled, length, src, dst, tick, blockData, hblankRequestTicks, hblankRequestAge, nextHblankRequestTicks, nextHblankRequestAge, sourceBytesTransferred, cpuBusValue, stopAfterCurrentBlock, preserveLengthAfterCurrentBlock, speedSwitchInProgress, speedSwitchStartedWithoutRequest, pauseOamDmaForSpeedSwitchBurst, wakeRequestArbitration, gpuLine, gpuTicksInLine, gpuCpuClockRephased, hblankStartTicksInLine, cpuHalted, haltHdmaState, haltEnteredThisTick, requestOverlappedCpuWrite, interruptEntryWonArbitration, cpuRequestArbitration, cpuRequestAllowsLateInterrupt, haltOpcodeRequestLatched
- `eu.rekawek.coffeegb.core.memory.GbcRam$GbcRamState`: ram, svbk
- `eu.rekawek.coffeegb.core.memory.BiosShadow$BiosShadowState`: isEnabled
- `eu.rekawek.coffeegb.core.memory.Ram$RamState`: space
- `eu.rekawek.coffeegb.core.memory.Mmu$MmuState`: ramC000Memento, ramD000Memento, ramFF80Memento, gbcRamMemento, undocumentedGbcRegistersMemento, oamEchoRamMemento
- `eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery$MemoryBatteryState`: buffer
- `eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery$FileBatteryState`: clockBuffer, ramBuffer, isClockPresent, isDirty
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc1$Mbc1State`: batteryMemento, ram, selectedRamBank, selectedRomBank, memoryModel, ramWriteEnabled, cachedRomBankFor0x0000, cachedRomBankFor0x4000, ramUpdated, wideBank, upperRegisterUsed
- `eu.rekawek.coffeegb.core.memory.cart.type.BungEms$BungEmsState`: batteryMemento, ram, romBankLow, romBankHigh, romBankMask, romBankLatch, selectedRamBank, configureMode, ramEnabled, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc2$Mbc2State`: batteryMemento, ram, selectedRomBank, ramWriteEnabled, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc7Eeprom$EepromState`: eeprom, state, bitsRead, command, address, writeValue, writeEnabled, sk, doBit
- `eu.rekawek.coffeegb.core.memory.cart.type.BasicRom$BasicRomState`: batteryMemento, ram, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc5$Mbc5State`: batteryMemento, ram, selectedRamBank, selectedRomBank, ramWriteEnabled, ramUpdated, motorOn
- `eu.rekawek.coffeegb.core.memory.cart.type.PocketCamera$PocketCameraState`: ram, cameraRegisters, romBank, ramBank, ramEnabled, cameraMapped
- `eu.rekawek.coffeegb.core.memory.cart.type.BhgosMulticart$BhgosMulticartState`: batteryMemento, ram, selectedRomBank, selectedRamBank, baseRomBank, blockSelectWrites, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc7$Mbc7State`: selectedRomBank, ramWriteEnabled1, ramWriteEnabled2, x, y, latchX, latchY, latchState, eepromMemento
- `eu.rekawek.coffeegb.core.memory.cart.type.MakonNtOld2$MakonNtOld2State`: batteryMemento, ram, selectedRomBank, mappedRomBank, baseRomBank, gameRomBankMask, weirdMode, rumbleEnabled, motorOn, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Sintax$SintaxState`: delegateMemento, xorValues, mode, bankNo, romBankXor
- `eu.rekawek.coffeegb.core.memory.cart.type.WisdomTree$WisdomTreeState`: bank
- `eu.rekawek.coffeegb.core.memory.cart.type.Huc3$Huc3State`: batteryMemento, ram, romBank, ramBank, mode, minutes, days, alarmMinutes, alarmDays, alarmEnabled, accessIndex, accessFlags, readValue, lastRtcSecond, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.DuzMulticart$DuzMulticartState`: ram, regs, selectedBank, selectedRamBank, baseBank, regIndex, ramWriteEnabled
- `eu.rekawek.coffeegb.core.memory.cart.type.Mani32kMulticart$Mani32kState`: block
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc6$Mbc6State`: ram, flash, ramEnabled, ramBankA, ramBankB, romBankA, romBankB, romBankAFlash, romBankBFlash, flashEnabled, flashWriteEnable, flashCommandState, flashIdMode, flashProgramMode
- `eu.rekawek.coffeegb.core.memory.cart.type.Huc1$Huc1State`: batteryMemento, ram, romBank, ramBank, irMode, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc$SachenState`: unmaskedBank, mask, base, lockState, transition, serveBootLogo
- `eu.rekawek.coffeegb.core.memory.cart.type.Mbc3$Mbc3State`: ram, clockMemento, batteryMemento, selectedRamBank, selectedRomBank, ramEnabled
- `eu.rekawek.coffeegb.core.memory.cart.type.Bbd$BbdState`: delegateMemento, dataSwapMode, bankSwapMode
- `eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart$SlMulticartState`: ram, configCommand, baseRomBank, selectedRomBank, romBankMask, zeroRemap, configurationMode, mbc5Mode, ramAllowed, ramEnabled, baseRamBank, selectedRamBank, ramBankMask
- `eu.rekawek.coffeegb.core.memory.cart.type.Mmm01$Mmm01State`: batteryMemento, ram, ramEnabled, romBankLow, romBankMid, romBankHigh, romBankMask, ramBankLow, ramBankHigh, ramBankMask, locked, mbc1Mode, mbc1ModeDisable, multiplexMode, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Tama5$Tama5State`: batteryMemento, ram, selectedReg, registers, rtcTimerPage, rtcAlarmPage, rtcFreePage0, rtcFreePage1, rtcDisabled, lastRtcSecond, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Datel$DatelState`: ram, regs, ramWritten, regsB, flash, flashCycle, flashErasePending, flashIdMode, launched, slotMemento
- `eu.rekawek.coffeegb.core.memory.cart.Cartridge$CartridgeState`: memoryControllerMemento, batteryMemento
- `eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock$RealTimeClockState`: seconds, minutes, hours, days, halt, counterOverflow, subSecondTicks, latched, latchedSeconds, latchedMinutes, latchedHours, latchedDays, latchedHalt, latchedCounterOverflow
- `eu.rekawek.coffeegb.core.memory.UndocumentedGbcRegisters$UndocumentedGbcRegistersState`: ramMemento, xff6c
- `eu.rekawek.coffeegb.core.memory.Dma$DmaState`: transferInProgress, restarted, from, ticks, transferClocks, oamOwnedForPpuBeforeTick, oamOwnedForPpu, ppuOamOwnedThroughRestart, cpuClockPaused, pauseEntryClocks, currentByte, regValue, pendingInterruptWriteByte, pendingInterruptWriteValue, vramDmaBusCollisionObserved
- `eu.rekawek.coffeegb.core.memory.OamEchoRam$OamEchoRamState`: ram
- `eu.rekawek.coffeegb.core.Gameboy$GameboyState`: biosShadowMemento, cartridgeMemento, gpuMemento, statRegisterMemento, mmuMemento, oamRamMemento, cpuMemento, interruptManagerMemento, timerMemento, dmaMemento, hdmaMemento, displayMemento, soundMemento, serialPortMemento, infraredPortMemento, codeBreakerRumbleMemento, joypadMemento, speedModeMemento, superGameboyMemento, backgroundMemento, vRamTransferMemento, sgbDisplayMemento, genieMemento, requestScreenRefresh, lcdDisabled, lcdOffTicks, speedSwitchTailTicks, speedSwitchClockPhaseShifted, blankCgbBootTilePending, clearBootTilemapPending, clearCgbBootOamShadowPending
- `eu.rekawek.coffeegb.core.gpu.IntQueue$IntQueueState`: array, size, offset
- `eu.rekawek.coffeegb.core.gpu.GpuRegisterValues$GpuRegisterValuesState`: values, mixValues, pendingMixValues, wxJustChangedTicks, scxOldValue, pendingScxOldValue
- `eu.rekawek.coffeegb.core.gpu.Lcdc$LcdcState`: value, mixValue, pendingMixValue, dmgBlobBackgroundEnable, pendingDmgBlobBackgroundEnable, tileSelectGlitchTicks, pendingTileSelectGlitchTicks, tileSelectGlitchHistory, oamSizeHistory
- `eu.rekawek.coffeegb.core.gpu.Display$DisplayState`: buffer, i, enabled, lastFrame, firstFrameAfterLcdEnable
- `eu.rekawek.coffeegb.core.gpu.Gpu$GpuState`: videoRam0Memento, videoRam1Memento, displayMemento, lcdcMemento, bgPaletteMemento, oamPaletteMemento, oamSearchPhaseMemento, pixelTransferPhaseMemento, pixelMachineMemento, rMemento, lcdEnabled, displayEnabledDelay, line, ticksInLine, firstLine, lcdEnableClockPhase, firstFrameAfterLcdEnable, pixelTransferDone, hblankIntFrom, mode0IntFrom, statModeLatchRephasedBySpeedSwitch, speedSwitchCompletedThisLine, lyReadLatchRephasedBySpeedSwitch, scxWrittenThisLine, doubleSpeedMode2DispatchStatTailThisLine, doubleSpeedMode2DispatchCrossedLineEdge, earlyScxStatTailThisLine, wyWrittenThisLine, lateDoubleSpeedLineZeroWindowEnable, lastCpuVramWriteTick, mode, pendingPpuWrites, cpuVisiblePpuRegisters
- `eu.rekawek.coffeegb.core.gpu.StatRegister$StatRegisterState`: enableBits, registeredLy, coincidence, intCoincidence, intLine, lycWriteSuppressed, suppressedLycIrqLine, modeBlockedLycIrqLine, lycIrqStatSource, lycIrqValueSource, lycIrqStatLatch, lycIrqValueLatch, lycIrqClock, nextLycIrqEvent, pendingLycWriteIrq, pendingLycComparatorIrq, lastLycIrqRegisterChangeClock, lastLcdcInterruptAcknowledgeClock, lastVBlankInterruptAcknowledgeClock, releaseTailLycCpuAcceptance, lycComparatorSignal, modeIrqStatLatch, modeIrqLycLatch, pendingModeIrqStat, pendingModeIrqLyc, pendingModeIrqStatClock, pendingModeIrqLycClock, mode0IrqStatLatch, mode0IrqLycLatch, pendingMode0IrqStat, pendingMode0IrqLyc, pendingMode0IrqStatClock, pendingMode0IrqLycClock, lastModeIrqStatWriteClock, lastModeIrqStatWriteLineTick, lastModeIrqStatWriteOld, cgbMode1IfClearAtCapture, pendingCgbMode1Interrupt, dmgLyc143Mode1CaptureClock, mode0EventArmed, previousMode0Window, previousMode1Window, previousMode2Window, pendingCgbMode0Interrupt, pendingCgbMode2Interrupt, pendingCgbMode2IfHighAtCapture, pendingCgbMode2LateReplay, pendingCgbMode2PublicationClock, cgbMode2CapturedAtLineEdge, pendingCgbFrameMode2Interrupt, retractableCgbMode2Interrupt, ordinaryHaltWakeStatClock, previousOrdinaryHaltWakePhase, scxChangedSinceMode0Event
- `eu.rekawek.coffeegb.core.gpu.VRamTransfer$VRamTransferState`: buffer, i
- `eu.rekawek.coffeegb.core.gpu.SpriteFifo$SpriteFifoState`: pixel, palette, priority, bgPriority, head, size, underflow
- `eu.rekawek.coffeegb.core.gpu.ColorPalette$ColorPaletteState`: palettes, index, autoIncrement
- `eu.rekawek.coffeegb.core.gpu.DmgPixelFifo$DmgPixelFifoState`: pixels, spriteFifo, delayEntry, delayStamp, delayHead, delaySize, outputTicks
- `eu.rekawek.coffeegb.core.gpu.phase.HBlankPhase$HBlankPhaseState`: ticks
- `eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer$PixelTransferState`: fetcherMemento, fifoMemento, entryTicks, lcdEnableFirstLine, position, window, windowBeingFetched, windowLineCounter, spriteOrder, spriteCount, spriteHead, objStep, objTileId, objAttributesValue, objData0, objTileLine, objData1Pending, objRefreshAge, objRefreshPops, objRefreshD0, objRefreshTileId, objRefreshLine, objRefreshAttrsValue, objRefreshZip, objWaiting, objectTimingPenalty, previousScx, fineScxRephasedThisLine, machineActive, windowPendingTicks, windowPendingWx, windowPendingPos, windowActivatedThisLine, previousWindowDisplay, cgbWindowStartTicks, cgbTerminalWindowStartedThisLine, insertBgPixel, machineStall, windowYTriggered, windowWy, pendingWindowWy, windowWyDelay, windowWyOldOnWriteTick, windowDisplayOverride, pendingWindowDisplayWrites, windowXOverride, pendingWindowXWrites
- `eu.rekawek.coffeegb.core.gpu.phase.OamSearch$SpritePosition$SpritePositionState`: x, y, address, enabled
- `eu.rekawek.coffeegb.core.gpu.phase.VBlankPhase$VBlankPhaseState`: ticks
- `eu.rekawek.coffeegb.core.gpu.phase.OamSearch$OamSearchState`: sprites, oamReaderY, oamReaderX, oamReaderInitialized, oamReaderBusY, oamReaderBusX, oamReaderDmaSource, oamReaderSourceChangeTicks, spritePosIndex, state, spriteY, spriteHeight, previousOamSpriteHeight, spriteHeightTransitionThisLine, spriteX, dmaBlockedThisLine, i, selectSprites, spriteCandidateSeen
- `eu.rekawek.coffeegb.core.gpu.ColorPixelFifo$ColorPixelFifoState`: pixels, palettes, priorities, spriteFifo, delayEntry, delayStamp, delayHead, delaySize, outputTicks, linePixels, clearedPixels, clearedPalettes, clearedPriorities
- `eu.rekawek.coffeegb.core.gpu.Fetcher$FetcherState`: pixelLine, state, windowTileX, fetcherY, tileMapAddress, tileId, tileAttributesValue, tileData1, tileData2, insertionGlitchDisabled, data2Pending, data2Delay, data2TileSelectGlitch
- `eu.rekawek.coffeegb.core.ir.FullChanger$FullChangerState`: schedule, armed, running, index, remaining
- `eu.rekawek.coffeegb.core.ir.InfraredPort$InfraredPortState`: rp, fullChangerMemento
- `eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint$Peer2PeerSerialEndpointState`: sb, bitsReceived, bitIndex
- `eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint$PrinterState`: state, commandId, compression, lengthLeft, commandData, commandLength, checksum, status, byteToSend, image, imageOffset, compressionRunLength, compressionRunIsCompressed, printCountdown, currentReply, sendBits, sb
- `eu.rekawek.coffeegb.core.serial.SerialPort$SerialPortState`: sb, sc, serialClocks, serialClockSignal, receivedBits, haltWakeDelay
- `eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint$GpsReceiverState`: ticks, nextStartupBeacon, startupBeacons, outputBytes, outputByte, outputBit, outputTicksRemaining, outputDelayTicks, serialInputHigh, sb, receiveBit, receiveByte, receiveOnes, receiveParityValid, capturingTaip, taipCommand
- `eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint$ByteReceivingSerialEndpointState`: sb, bits
- `eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint$BarcodeBoyState`: state, handshakeByte, sendBitIndex, data, dataByte, recvBitIndex, clockDivider
- `eu.rekawek.coffeegb.core.serial.FourPlayerAdapter$AdapterState`: sb, transferArmed, pendingBits, connected, consecutiveFf, replies, transmissionBuffer, packetByte, bit, ticksUntilBit, rate, size, phase, transmissionRequested, restartPingRequested
- `eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble$CodeBreakerRumbleState`: motorOn
- `eu.rekawek.coffeegb.core.genie.Genie$GameGeniePatchState`: newData, address, oldData
- `eu.rekawek.coffeegb.core.genie.Genie$GameSharkPatchState`: mode, bank, address, data
- `eu.rekawek.coffeegb.core.gpu.Gpu$PendingPpuWriteState`: address, value, mask, remainingDots
- `eu.rekawek.coffeegb.core.gpu.phase.PixelTransfer$DelayedWindowWriteState`: value, remainingDots
- `eu.rekawek.coffeegb.core.memory.cart.type.XploderGb$XploderGbState`: batteryMemento, ram, selectedRomBank, selectedRamBank, ramUpdated
- `eu.rekawek.coffeegb.core.memory.cart.type.Vf001Zook$Vf001ZookState`: delegateMemento, stream, streamLength, bankPortRun
- `eu.rekawek.coffeegb.core.memory.cart.type.Vf001General$Vf001GeneralState`: delegateMemento, configMode, runningValue, cur6000, cur700x, sequenceStartBank, sequenceStartAddress, sequenceLength, sequence, sequenceBytesLeft, replaceBankZero, replacementStartAddress, replacementSourceBank, selectedRomBank
- `eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine$MobileAdapterEngineState`: phaseId, outcomeId, errorId, deviceId, packetBuffer, packetCount, expectedPacketBytes, configuration, responsePacket, acknowledgement, idlePhaseUnits, serialByteObserved, pendingPacketSlots
- `eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint$MobileAdapterSerialEndpointState`: engineState, sb, sendBitIndex
- `eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine$MobileAdapterEngineNetworkState`: phaseId, outcomeId, errorId, deviceId, packetBuffer, packetCount, expectedPacketBytes, configuration, responsePacket, acknowledgement, idlePhaseUnits, serialByteObserved, pendingPacketSlots, externalIoAtCapture
- `eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint$MobileAdapterSerialEndpointNetworkState`: engineState, sb, sendBitIndex
- `eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint$MobileAdapterSerialEndpointWireState`: engineState, sb, sendBitIndex, byteTransferActive, wirePhaseId, currentReply, requestAcknowledgement, responsePacket, responseByteIndex, awaitingResponse, responseRetryCount
