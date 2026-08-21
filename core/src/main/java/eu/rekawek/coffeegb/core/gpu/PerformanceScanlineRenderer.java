package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;

import java.util.Objects;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.BGP;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.OBP0;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.OBP1;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.SCX;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.SCY;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.WX;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.WY;

/**
 * A deliberately approximate, line-at-a-time renderer for the PERFORMANCE execution mode.
 *
 * <p>The regular renderer is a dot pipeline because the CPU can observe VRAM, OAM, LCDC and
 * palette writes while mode 3 is in progress. This renderer takes one coherent snapshot at
 * mode-3 entry and produces all 160 visible pixels with ordinary tile/sprite lookups. It is
 * therefore only suitable for a caller that has already opted into PERFORMANCE's relaxed
 * mid-line-write contract. Writes made after the line begins are treated as next-line writes.
 * Timing, STAT, VRAM/OAM lock and window-line bookkeeping remain the caller's responsibility.</p>
 *
 * <p>The class is intentionally independent from {@link Gpu}. That keeps the high-risk visual
 * shortcut easy to differential-test and gives the timing GPU a small integration seam: create
 * one instance with its existing VRAM/OAM/register objects, then call
 * {@link #renderLine(Display, int, int)} at mode-3 entry. The overload accepting an output
 * array is useful for tests and frontends which already own their frame buffer.</p>
 */
public final class PerformanceScanlineRenderer {

    private static final int SCREEN_WIDTH = Display.DISPLAY_WIDTH;

    private final AddressSpace videoRam0;

    private final AddressSpace videoRam1;

    private final AddressSpace oamRam;

    private final Lcdc lcdc;

    private final GpuRegisterValues registers;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final boolean gbc;

    // KEY0 can switch a live CGB between native and DMG-compatible output after boot. Keep this
    // line renderer session-owned but refresh the mode at the same owner-thread boundary as the
    // two PixelTransfer machines.
    private boolean dmgCompat;

    private final SpritePosition[] sprites;

    // Scratch storage is retained across lines. A performance line should not allocate an
    // object for every tile or sprite, especially on Android's controller thread.
    private final int[] linePixels = new int[SCREEN_WIDTH];

    private final SpriteLine[] spriteLines;

    private final int[] spriteOrder;

    private int spriteCount;

    // The renderer is owner-threaded by the emulator. Keep the current background sample in
    // primitive fields instead of returning a temporary record for every visible pixel.
    private int currentBackgroundRaw;

    private int currentBackgroundPalette;

    private boolean currentBackgroundPriority;

    /**
     * Creates a line renderer over the live PPU memory/register objects.
     *
     * @param videoRam0 bank 0 VRAM, accepting addresses {@code 0x8000..0x9fff}
     * @param videoRam1 bank 1 VRAM, or {@code null} for DMG
     * @param oamRam OAM, accepting addresses {@code 0xfe00..0xfe9f}
     * @param lcdc line-start LCDC register view
     * @param registers line-start scroll/palette register view
     * @param bgPalette CGB background palette RAM; ignored for DMG
     * @param oamPalette CGB object palette RAM; ignored for DMG
     * @param gbc whether the physical PPU is a CGB
     * @param dmgCompat whether a CGB is currently in DMG-compatibility mode
     * @param sprites the selected OAM entries from {@code OamSearch}; disabled entries are
     *                ignored
     */
    public PerformanceScanlineRenderer(
            AddressSpace videoRam0,
            AddressSpace videoRam1,
            AddressSpace oamRam,
            Lcdc lcdc,
            GpuRegisterValues registers,
            ColorPalette bgPalette,
            ColorPalette oamPalette,
            boolean gbc,
            boolean dmgCompat,
            SpritePosition[] sprites) {
        this.videoRam0 = Objects.requireNonNull(videoRam0, "videoRam0");
        this.videoRam1 = videoRam1;
        this.oamRam = Objects.requireNonNull(oamRam, "oamRam");
        this.lcdc = Objects.requireNonNull(lcdc, "lcdc");
        this.registers = Objects.requireNonNull(registers, "registers");
        this.bgPalette = Objects.requireNonNull(bgPalette, "bgPalette");
        this.oamPalette = Objects.requireNonNull(oamPalette, "oamPalette");
        this.gbc = gbc;
        this.dmgCompat = dmgCompat;
        this.sprites = Objects.requireNonNull(sprites, "sprites");
        if (sprites.length > 10) {
            throw new IllegalArgumentException("A Game Boy line can select at most 10 sprites");
        }
        this.spriteLines = new SpriteLine[sprites.length];
        this.spriteOrder = new int[sprites.length];
        for (int i = 0; i < spriteLines.length; i++) {
            spriteLines[i] = new SpriteLine();
        }
    }

    /** Updates the CGB compatibility palette mapping without rebuilding the renderer. */
    public void setDmgCompat(boolean dmgCompat) {
        this.dmgCompat = dmgCompat;
    }

    /**
     * Renders a line directly into the display's private write cursor.
     *
     * <p>DMG output values are palette-resolved shade indices (0..3), while CGB output values
     * are raw RGB555 values, matching {@link Display#putDmgPixel(int)} and
     * {@link Display#putColorPixel(int)} respectively.</p>
     */
    public void renderLine(Display display, int ly, int windowLine) {
        Objects.requireNonNull(display, "display");
        renderLine(ly, windowLine, linePixels);
        if (gbc) {
            for (int pixel : linePixels) {
                display.putColorPixel(pixel);
            }
        } else {
            for (int pixel : linePixels) {
                display.putDmgPixel(pixel);
            }
        }
    }

    /**
     * Renders a line into {@code output} without allocating. See
     * {@link #renderLine(Display, int, int)} for the output format.
     */
    public void renderLine(int ly, int windowLine, int[] output) {
        Objects.requireNonNull(output, "output");
        if (output.length < SCREEN_WIDTH) {
            throw new IllegalArgumentException("Scanline output must contain 160 pixels");
        }
        prepareSprites(ly);

        int scx = registers.get(SCX) & 0xff;
        int scy = registers.get(SCY) & 0xff;
        int wy = registers.get(WY) & 0xff;
        int wx = registers.get(WX) & 0xff;
        boolean bgEnabled = lcdc.isBgAndWindowDisplay();
        boolean windowEnabled = lcdc.isWindowDisplay()
                && (gbc || bgEnabled)
                && ly >= wy
                && wx < (gbc ? 167 : 166);
        int windowLeft = wx <= 7 ? 0 : wx - 7;
        int windowY = windowLine >= 0 ? windowLine : Math.max(0, ly - wy);
        int bgMap = lcdc.getBgTileMapDisplay();
        int windowMap = lcdc.getWindowTileMapDisplay();

        for (int screenX = 0; screenX < SCREEN_WIDTH; screenX++) {
            boolean useWindow = windowEnabled && screenX >= windowLeft;
            int mapX;
            int mapY;
            int mapBase;
            if (useWindow) {
                mapX = screenX - windowLeft;
                mapY = windowY;
                mapBase = windowMap;
            } else {
                mapX = (scx + screenX) & 0xff;
                mapY = (scy + ly) & 0xff;
                mapBase = bgMap;
            }
            sampleBackground(mapBase, mapX, mapY);
            if (!gbc || dmgCompat) {
                if (!bgEnabled) {
                    currentBackgroundRaw = 0;
                    currentBackgroundPriority = false;
                }
            }

            int sprite = spriteAt(screenX);
            output[screenX] = resolvePixel(sprite, bgEnabled);
        }
    }

    /**
     * Predicts the absolute line tick at which mode 3 should hand off.
     *
     * <p>This is intentionally a cheap scheduler hint, not a timing model. It starts with the
     * calibrated background cost ({@code 248 + SCX%8}), adds six dots for a visible window
     * startup and a conservative object-fetch cost for each selected visible sprite. It does
     * not model fetcher phase, sprite overlap, mid-line writes, DMA contention, CGB startup
     * variants, or the final one-dot STAT visibility delay. Callers must clamp/use it as a
     * prediction and fall back to the scalar cursor when exact timing is required.</p>
     */
    public int predictMode3End(int ly) {
        int scx = registers.get(SCX) & 0xff;
        int result = 248 + (scx & 7);
        int wy = registers.get(WY) & 0xff;
        int wx = registers.get(WX) & 0xff;
        boolean bgEnabled = lcdc.isBgAndWindowDisplay();
        boolean window = lcdc.isWindowDisplay()
                && (gbc || bgEnabled)
                && ly >= wy
                && wx < (gbc ? 167 : 166);
        if (window) {
            result += 6;
        }
        prepareSprites(ly);
        for (int i = 0; i < spriteCount; i++) {
            int left = spriteLines[i].left;
            // OAM still selects these entries, but a sprite wholly outside the 160-pixel
            // viewport cannot contribute visible pixels or a useful mode-3 fetch in this
            // approximate predictor. Keep partially clipped sprites (x=1..7 and x=160..167)
            // because they do affect the visible edge and their fetch cost remains relevant.
            if (left >= SCREEN_WIDTH || left + 8 <= 0) {
                continue;
            }
            // The real object fetch waits for a fetcher boundary before its fixed six-dot
            // sequence. Five dots is a safe upper bound for that wait in the common path.
            result += 6 + Math.min(5, Math.max(0, (left + scx) & 7));
        }
        return Math.min(455, result);
    }

    private void sampleBackground(int mapBase, int x, int y) {
        int tileX = (x >> 3) & 0x1f;
        int tileY = (y >> 3) & 0x1f;
        int mapAddress = mapBase + (tileY << 5) + tileX;
        int tileId = videoRam0.getByte(mapAddress) & 0xff;
        int attributeValue = 0;
        if (gbc && videoRam1 != null) {
            attributeValue = videoRam1.getByte(mapAddress) & 0xff;
        }
        TileAttributes attributes = TileAttributes.valueOf(attributeValue);
        int row = y & 7;
        if (attributes.isYflip()) {
            row = 7 - row;
        }
        int bank = gbc ? attributes.getBank() : 0;
        int address = tileAddress(tileId, row, lcdc.isBgWindowTileDataSigned());
        int low = readVideo(bank, address);
        int high = readVideo(bank, address + 1);
        int bit = attributes.isXflip() ? x & 7 : 7 - (x & 7);
        currentBackgroundRaw = ((low >> bit) & 1) | (((high >> bit) & 1) << 1);
        currentBackgroundPalette = attributes.getColorPaletteIndex();
        currentBackgroundPriority = attributes.isPriority();
    }

    private int spriteAt(int x) {
        if (!lcdc.isObjDisplay()) {
            return -1;
        }
        for (int order = 0; order < spriteCount; order++) {
            SpriteLine sprite = spriteLines[spriteOrder[order]];
            int pixelX = x - sprite.left;
            if (pixelX < 0 || pixelX >= 8) {
                continue;
            }
            int bit = sprite.xFlip ? pixelX : 7 - pixelX;
            int raw = ((sprite.low >> bit) & 1) | (((sprite.high >> bit) & 1) << 1);
            if (raw != 0) {
                sprite.pixel = raw;
                return spriteOrder[order];
            }
        }
        return -1;
    }

    private int resolvePixel(int spriteIndex, boolean bgEnabled) {
        if (!gbc) {
            if (spriteIndex >= 0) {
                SpriteLine sprite = spriteLines[spriteIndex];
                if (!(sprite.priority && currentBackgroundRaw != 0)) {
                    int palette = sprite.palette == 0 ? registers.get(OBP0) : registers.get(OBP1);
                    return (palette >> (sprite.pixel * 2)) & 0x03;
                }
            }
            return resolveDmgBackground();
        }
        return resolveCgb(spriteIndex, bgEnabled);
    }

    private int resolveDmgBackground() {
        return (registers.get(BGP) >> (currentBackgroundRaw * 2)) & 0x03;
    }

    private int resolveCgb(int spriteIndex, boolean bgEnabled) {
        if (spriteIndex >= 0) {
            SpriteLine sprite = spriteLines[spriteIndex];
            if (sprite.pixel != 0 && cgbSpriteIsVisible(sprite, bgEnabled)) {
                int pixel = sprite.pixel;
                if (dmgCompat) {
                    int obp = registers.get(sprite.palette == 0 ? OBP0 : OBP1);
                    pixel = (obp >> (pixel * 2)) & 0x03;
                    return oamPalette.getPalette(sprite.palette)[pixel];
                }
                return oamPalette.getPalette(sprite.palette)[pixel];
            }
        }
        int pixel = currentBackgroundRaw;
        if (dmgCompat) {
            if (!bgEnabled) {
                pixel = 0;
            } else {
                pixel = (registers.get(BGP) >> (pixel * 2)) & 0x03;
            }
        }
        return bgPalette.getPalette(currentBackgroundPalette)[pixel];
    }

    private boolean cgbSpriteIsVisible(SpriteLine sprite, boolean bgEnabled) {
        if (!bgEnabled && !dmgCompat) {
            return true;
        }
        if (currentBackgroundPriority || sprite.priority) {
            return currentBackgroundRaw == 0;
        }
        return true;
    }

    private void prepareSprites(int ly) {
        spriteCount = 0;
        int spriteHeight = lcdc.getSpriteHeight();
        for (int i = 0; i < sprites.length; i++) {
            SpritePosition position = sprites[i];
            if (!position.isEnabled()) {
                continue;
            }
            int top = position.getY() - 16;
            int row = ly - top;
            if (row < 0 || row >= spriteHeight) {
                continue;
            }
            SpriteLine line = spriteLines[spriteCount];
            line.address = position.getAddress();
            line.left = position.getX() - 8;
            int tileId = oamRam.getByte(position.getAddress() + 2) & 0xff;
            int attributesValue = oamRam.getByte(position.getAddress() + 3) & 0xff;
            line.priority = (attributesValue & 0x80) != 0;
            line.xFlip = (attributesValue & 0x20) != 0;
            line.palette = gbc && !dmgCompat ? attributesValue & 7
                    : ((attributesValue & 0x10) == 0 ? 0 : 1);
            if ((attributesValue & 0x40) != 0) {
                row = spriteHeight - 1 - row;
            }
            if (spriteHeight == 16) {
                tileId &= 0xfe;
                if (row >= 8) {
                    tileId++;
                    row -= 8;
                }
            }
            int bank = gbc && (attributesValue & 0x08) != 0 ? 1 : 0;
            int address = tileAddress(tileId, row, false);
            line.low = readVideo(bank, address);
            line.high = readVideo(bank, address + 1);
            line.pixel = 0;
            // Find a non-transparent pixel only to keep the candidate's palette and priority
            // available to the composition loop. The exact pixel is refreshed in spriteAt.
            spriteOrder[spriteCount] = spriteCount;
            spriteCount++;
        }

        // SpritePosition entries are normally already in OAM order. Sorting here is cheap and
        // makes the seam safe for tests and future callers that pass a custom selection order.
        for (int i = 1; i < spriteCount; i++) {
            int candidate = spriteOrder[i];
            int j = i - 1;
            while (j >= 0 && comesAfter(spriteLines[spriteOrder[j]], spriteLines[candidate])) {
                spriteOrder[j + 1] = spriteOrder[j];
                j--;
            }
            spriteOrder[j + 1] = candidate;
        }
    }

    private boolean comesAfter(SpriteLine first, SpriteLine second) {
        if (gbc) {
            return first.address > second.address;
        }
        if (first.left != second.left) {
            return first.left > second.left;
        }
        return first.address > second.address;
    }

    private int tileAddress(int tileId, int row, boolean signed) {
        if (signed) {
            return 0x9000 + ((byte) tileId) * 16 + row * 2;
        }
        return 0x8000 + tileId * 16 + row * 2;
    }

    private int readVideo(int bank, int address) {
        if (bank != 0 && videoRam1 != null) {
            return videoRam1.getByte(address) & 0xff;
        }
        return videoRam0.getByte(address) & 0xff;
    }

    private final class SpriteLine {
        private int address;
        private int left;
        private int low;
        private int high;
        private int palette;
        private boolean priority;
        private boolean xFlip;
        private int pixel;
    }
}
