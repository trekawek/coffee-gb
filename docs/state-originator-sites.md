# Production capture-site audit

This manifest accounts for every production source file that declares the explicit
`StatefulComponent` contract or participates in `captureState`/detached capture. `StateInventoryTest`
scans the production trees and requires an
exact path match, so a new capture owner or call site cannot be omitted from review.

`OWNER` means that the file owns behavior fields in the record(s) named in
[`state-memento-schema.md`](state-memento-schema.md); those record-component lists are the exact
non-serializable component-state fields. `DmgPixelFifo` additionally exposes the six primitive fields listed there
through the detached runtime supplement, preserving its pinned record descriptor. Arrays and
collections become deep-owned at the detached boundary. `COMPOSITE`
means that the owner captures child states and any root fields listed in that schema. `WORKFLOW`
is a controller call site which retains no additional machine field. `CONTRACT` is an interface and
owns no instance state. Derived caches and external services omitted by each owner are documented
by subsystem in [`state-machine-inventory.md`](state-machine-inventory.md); none of those services
is admitted to the detached graph.

For internal rewind only, array-owning sites additionally implement the
`captureState(MachineStateCapture)` safe-point overload. That overload constructs a transient
record view with registered borrowed primitive arrays; `MachineSnapshot` compares/copies it
synchronously and rejects any unregistered array. It does not alter the listed record components,
the ordinary deep-owned `captureState()` contract, or any portable/legacy byte schema.

- WORKFLOW `controller/src/main/java/eu/rekawek/coffeegb/controller/BasicController.kt`
- WORKFLOW `controller/src/main/java/eu/rekawek/coffeegb/controller/RewindManager.kt`
- COMPOSITE `controller/src/main/java/eu/rekawek/coffeegb/controller/Session.kt`
- WORKFLOW `controller/src/main/java/eu/rekawek/coffeegb/controller/link/LinkedController.kt`
- WORKFLOW `controller/src/main/java/eu/rekawek/coffeegb/controller/link/StateHistory.kt`
- WORKFLOW `controller/src/main/java/eu/rekawek/coffeegb/controller/state/DetachedState.kt`
- WORKFLOW `controller/src/main/java/eu/rekawek/coffeegb/controller/state/MachineSnapshot.kt`
- WORKFLOW `controller/src/main/java/eu/rekawek/coffeegb/controller/state/StateCodec.kt`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/Gameboy.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/cpu/Cpu.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/cpu/InterruptManager.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/cpu/Registers.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/cpu/SpeedMode.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/genie/Genie.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/ColorPalette.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/ColorPixelFifo.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/Display.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/DmgPixelFifo.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/Fetcher.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/gpu/Gpu.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/GpuRegisterValues.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/IntQueue.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/Lcdc.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/SpriteFifo.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/StatRegister.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/VRamTransfer.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/phase/HBlankPhase.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/phase/OamSearch.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/gpu/phase/PixelTransfer.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/gpu/phase/VBlankPhase.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/ir/FullChanger.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/ir/InfraredPort.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/joypad/Joypad.java`
- CONTRACT `core/src/main/java/eu/rekawek/coffeegb/core/state/MachineStateCapture.java`
- CONTRACT `core/src/main/java/eu/rekawek/coffeegb/core/state/StatefulComponent.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/BiosShadow.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/Dma.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/GbcRam.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/Hdma.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/Mmu.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/OamEchoRam.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/Ram.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/UndocumentedGbcRegisters.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/Cartridge.java`
- CONTRACT `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/MemoryController.java`
- CONTRACT `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/battery/Battery.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/battery/FileBattery.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/battery/MemoryBattery.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/rtc/RealTimeClock.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/BasicRom.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Bbd.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/BhgosMulticart.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/BungEms.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Datel.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/DuzMulticart.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Huc1.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Huc3.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/LiCheng.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/MakonNtOld2.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mani32kMulticart.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mbc1.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mbc2.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mbc3.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mbc5.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mbc6.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mbc7.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mbc7Eeprom.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Mmm01.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/PocketCamera.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/SachenMmc.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Sintax.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/SlMulticart.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Tama5.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Vf001General.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/Vf001Zook.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/WisdomTree.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/memory/cart/type/XploderGb.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/rumble/CodeBreakerRumble.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/serial/BarcodeBoySerialEndpoint.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/serial/ByteReceivingSerialEndpoint.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/serial/FourPlayerAdapter.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/serial/GameboyPrinterSerialEndpoint.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/serial/GpsReceiverSerialEndpoint.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/serial/Peer2PeerSerialEndpoint.java`
- CONTRACT `core/src/main/java/eu/rekawek/coffeegb/core/serial/SerialEndpoint.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/serial/SerialPort.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sgb/Background.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sgb/Commands.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sgb/SgbDisplay.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sgb/SuperGameboy.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/AbstractSoundMode.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/FrameSequencer.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/FrequencySweep.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/LengthCounter.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/Lfsr.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/PolynomialCounter.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/sound/Sound.java`
- COMPOSITE `core/src/main/java/eu/rekawek/coffeegb/core/sound/SoundMode1.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/SoundMode2.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/SoundMode3.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/SoundMode4.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/sound/VolumeEnvelope.java`
- OWNER `core/src/main/java/eu/rekawek/coffeegb/core/timer/Timer.java`
