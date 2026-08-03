package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;

/**
 * The Pocket Camera cartridge (type 0xFC): MBC5-like ROM banking, 128 KB of banked RAM,
 * and the camera's register file mapped in place of RAM when RAM-bank bit 4 is set.
 * A capture completes instantly (the busy bit always reads 0). The sensor image comes,
 * in priority order, from a live {@link CameraSource} registered by the front end (the
 * Swing UI wires a real webcam through it), and finally a synthetic test pattern. File and image
 * decoding are desktop adapter responsibilities. The image is scaled to the
 * sensor's 128x112, converted to luminance and dithered through the game-supplied
 * threshold matrix (registers 0x06-0x35), and the result is written as 2bpp tiles to the
 * capture buffer at RAM offset 0x100 like the real ASIC does.
 */
public class PocketCamera implements MemoryController {

    private final int[] rom;

    private final int[] ram;

    private final Battery battery;

    private final int[] cameraRegisters = new int[0x36];

    private static final double[] GAIN_VALUES = {
            0.8809390, 0.9149149, 0.9457498, 0.9739758,
            1.0000000, 1.0241412, 1.0466537, 1.0677433,
            1.0875793, 1.1240310, 1.1568911, 1.1868043,
            1.2142561, 1.2396208, 1.2743837, 1.3157323,
            1.3525190, 1.3856512, 1.4157897, 1.4434309,
            1.4689574, 1.4926697, 1.5148087, 1.5355703,
            1.5551159, 1.5735801, 1.5910762, 1.6077008,
            1.6235366, 1.6386550, 1.6531183, 1.6669808};

    private int romBank = 1;

    private int ramBank;

    private boolean ramEnabled;

    private boolean cameraMapped;

    // A live capture source is registered by the front end. It is static because the cartridge is
    // constructed beneath the front-end session boundary and only one active machine owns it.
    private static volatile CameraSource cameraSource;

    /** Registers (or clears, with {@code null}) the live sensor source. */
    public static void setCameraSource(CameraSource source) {
        cameraSource = source;
    }

    public PocketCamera(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.ram = new int[0x20000];
        this.battery = battery;
        battery.loadRam(ram);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address < 0x2000) {
            ramEnabled = (value & 0x0f) == 0x0a;
        } else if (address < 0x4000) {
            romBank = value & 0x3f;
        } else if (address < 0x6000) {
            ramBank = value & 0x0f;
            cameraMapped = (value & 0x10) != 0;
        } else if (address >= 0xa000 && address < 0xc000) {
            if (cameraMapped) {
                int reg = address & 0x7f;
                if (reg < cameraRegisters.length) {
                    // the shoot bit completes instantly - never store the busy flag
                    cameraRegisters[reg] = reg == 0 ? (value & 0x06) : value;
                    if (reg == 0 && (value & 0x01) != 0) {
                        capture();
                    }
                }
            } else if (ramEnabled) {
                ram[(ramBank * 0x2000 + (address - 0xa000)) % ram.length] = value;
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address < 0x4000) {
            return rom[address % rom.length];
        } else if (address < 0x8000) {
            int bank = romBank % Math.max(1, rom.length / 0x4000);
            return rom[bank * 0x4000 + (address - 0x4000)];
        } else if (address >= 0xa000 && address < 0xc000) {
            if (cameraMapped) {
                // only register 0 is readable; the rest read as 0
                return (address & 0x7f) == 0 ? cameraRegisters[0] : 0;
            }
            // the camera cart serves RAM reads (notably the capture buffer at bank 0,
            // 0xA100) regardless of the RAM-enable latch - the game's auto-exposure
            // loop meters the image without enabling RAM first
            return ram[(ramBank * 0x2000 + (address - 0xa000)) % ram.length];
        }
        return 0xff;
    }

    /**
     * Processes one sensor frame into the capture buffer (RAM bank 0, offset 0x100):
     * 128x112 pixels dithered to 2 bpp through the per-position threshold triplets the
     * game programs into registers 0x06-0x35, stored as Game Boy tiles.
     */
    private void capture() {
        int[] luma = sensorImage();
        // sensor response: the game meters its auto-exposure loop against the output,
        // so the luminance must scale with the exposure and gain registers like the
        // real sensor (SameBoy's model: color * gain * exposure / 0x1000)
        double gain = GAIN_VALUES[cameraRegisters[1] & 0x1f];
        int exposure = (cameraRegisters[2] << 8) | cameraRegisters[3];
        for (int y = 0; y < 112; y++) {
            for (int x = 0; x < 128; x++) {
                int v = (int) (luma[y * 128 + x] * gain * exposure / 0x1000);
                int base = 6 + (((y & 3) * 4 + (x & 3)) * 3);
                int color; // 0 = white .. 3 = black
                if (v < cameraRegisters[base]) {
                    color = 3;
                } else if (v < cameraRegisters[base + 1]) {
                    color = 2;
                } else if (v < cameraRegisters[base + 2]) {
                    color = 1;
                } else {
                    color = 0;
                }
                int tile = (y / 8) * 16 + (x / 8);
                int row = y & 7;
                int addr = 0x100 + tile * 16 + row * 2;
                int bit = 7 - (x & 7);
                ram[addr] = (ram[addr] & ~(1 << bit)) | ((color & 1) << bit);
                ram[addr + 1] = (ram[addr + 1] & ~(1 << bit)) | (((color >> 1) & 1) << bit);
            }
        }
    }

    private int[] sensorImage() {
        // A registered live source is re-read for every capture and must never block the machine.
        CameraSource source = cameraSource;
        if (source != null) {
            try {
                CameraFrame frame = source.getFrame();
                if (frame != null) {
                    return toLuma(frame);
                }
            } catch (Exception e) {
                // Fall through to the test pattern. Host capture failures must not affect emulation.
            }
        }
        // synthetic test pattern: diagonal gradient with circles
        int[] luma = new int[128 * 112];
        for (int y = 0; y < 112; y++) {
            for (int x = 0; x < 128; x++) {
                int dx = x - 64, dy = y - 56;
                int r = (int) Math.sqrt(dx * dx + dy * dy);
                luma[y * 128 + x] = ((x + y) & 0xff) ^ ((r & 8) != 0 ? 0x60 : 0);
            }
        }
        return luma;
    }

    /**
     * Scales an RGB frame to the sensor's 128x112 with deterministic nearest-neighbour sampling,
     * then converts it to per-pixel luminance. The integer mapping is the established desktop
     * default and avoids host-specific image interpolation.
     */
    private static int[] toLuma(CameraFrame src) {
        int sourceWidth = src.getWidth();
        int sourceHeight = src.getHeight();
        int[] rgb = src.copyRgb();
        int[] luma = new int[128 * 112];
        for (int y = 0; y < 112; y++) {
            int sourceY = y * sourceHeight / 112;
            for (int x = 0; x < 128; x++) {
                int sourceX = x * sourceWidth / 128;
                int pixel = rgb[sourceY * sourceWidth + sourceX];
                luma[y * 128 + x] = ((pixel >> 16 & 0xff) * 77 + (pixel >> 8 & 0xff) * 151 + (pixel & 0xff) * 28) >> 8;
            }
        }
        return luma;
    }

    @Override
    public void flushRam() {
        battery.saveRam(ram);
        battery.flush();
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return new PocketCameraState(
                ram.clone(), cameraRegisters.clone(), romBank, ramBank, ramEnabled, cameraMapped);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new PocketCameraState(
                capture.ints(ram),
                capture.ints(cameraRegisters),
                romBank,
                ramBank,
                ramEnabled,
                cameraMapped);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        capture.declareInts(ram);
        capture.declareInts(cameraRegisters);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof PocketCameraState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        System.arraycopy(mem.cameraRegisters, 0, cameraRegisters, 0, cameraRegisters.length);
        this.romBank = mem.romBank;
        this.ramBank = mem.ramBank;
        this.ramEnabled = mem.ramEnabled;
        this.cameraMapped = mem.cameraMapped;
    }

    private record PocketCameraState(int[] ram, int[] cameraRegisters, int romBank, int ramBank,
                                       boolean ramEnabled, boolean cameraMapped)
            implements ComponentState<MemoryController> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record PocketCameraMemento(int[] ram, int[] cameraRegisters, int romBank, int ramBank,
                                       boolean ramEnabled, boolean cameraMapped)
            implements Memento<MemoryController> {
    }
}
