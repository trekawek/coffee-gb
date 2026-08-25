package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.BGP;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.OBP0;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.SCX;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.SCY;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.WX;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.WY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class PerformanceScanlineRendererTest {

    @Test
    public void rendersDmgBackgroundAndWindowWithoutAllocatingPixelsPerTile() {
        Fixture fixture = new Fixture(false, false);
        fixture.lcdc.set(0xf1); // LCD on, BG/window on, window on, window map at 9c00
        fixture.registers.put(BGP, 0xe4); // identity DMG palette
        fixture.registers.put(WY, 0);
        fixture.registers.put(WX, 7);
        fixture.fillTile(0, 0x00, 0xff); // raw color 2
        fixture.fillTile(1, 0xff, 0x00); // raw color 1
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, 0);
            fixture.vram0.setByte(0x9c00 + tile, 1);
        }

        int[] pixels = new int[160];
        fixture.renderer().renderLine(0, 0, pixels);

        for (int pixel : pixels) {
            assertEquals(1, pixel);
        }
        // Disable the window for the next coherent line snapshot. The background remains raw
        // color 2, proving the line renderer reads the two tile maps independently.
        fixture.lcdc.set(0x91);
        fixture.renderer().renderLine(0, 0, pixels);
        for (int pixel : pixels) {
            assertEquals(2, pixel);
        }
    }

    @Test
    public void rendersDmgSpriteWithOamPriorityAndPalette() {
        Fixture fixture = new Fixture(false, false);
        fixture.lcdc.set(0x93); // LCD on, OBJ on, BG/window on
        fixture.registers.put(BGP, 0xe4);
        fixture.registers.put(OBP0, 0xe4);
        fixture.fillTile(0, 0xff, 0x00); // background raw color 1
        fixture.fillTile(1, 0x00, 0xff); // sprite raw color 2
        fixture.oam.setByte(0xfe00, 16); // sprite top = line 0
        fixture.oam.setByte(0xfe01, 8); // sprite left = pixel 0
        fixture.oam.setByte(0xfe02, 1);
        fixture.oam.setByte(0xfe03, 0); // OBP0, no flip, no priority bit
        SpritePosition sprite = new SpritePosition();
        sprite.enable(8, 16, 0xfe00);
        fixture.sprites[0] = sprite;

        int[] pixels = new int[160];
        fixture.renderer().renderLine(0, -1, pixels);

        for (int i = 0; i < 8; i++) {
            assertEquals(2, pixels[i]);
        }
        for (int i = 8; i < pixels.length; i++) {
            assertEquals(1, pixels[i]);
        }
    }

    @Test
    public void rendersNativeCgbBankAttributesAndPalette() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.lcdc.set(0x91);
        fixture.registers.setGbc(true);
        fixture.fillTile(0, 0xff, 0x00); // raw color 1 in bank 0
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram1.setByte(0x9800 + tile, 3); // CGB BG palette 3
        }
        fixture.bgPalette.getPalette(3)[1] = 0x1234;

        int[] pixels = new int[160];
        fixture.renderer().renderLine(0, -1, pixels);

        int[] expected = new int[160];
        java.util.Arrays.fill(expected, 0x1234);
        assertArrayEquals(expected, pixels);
    }

    @Test
    public void mapsCgbDmgCompatibilityThroughDmgPaletteRegisters() {
        Fixture fixture = new Fixture(true, true);
        fixture.lcdc.setGbc(true);
        fixture.lcdc.set(0x91);
        fixture.registers.setGbc(true);
        fixture.registers.put(BGP, 0x0c); // raw color 1 -> CGB palette color 3
        fixture.fillTile(0, 0xff, 0x00);
        fixture.bgPalette.getPalette(0)[3] = 0x4567;

        int[] pixels = new int[160];
        fixture.renderer().renderLine(0, -1, pixels);

        for (int pixel : pixels) {
            assertEquals(0x4567, pixel);
        }
    }

    @Test
    public void predictorIncludesFineScrollWindowAndVisibleSprites() {
        Fixture fixture = new Fixture(false, false);
        fixture.lcdc.set(0xb3); // BG + OBJ + window, map choices do not affect the hint
        fixture.registers.put(SCX, 3);
        fixture.registers.put(WY, 0);
        fixture.registers.put(WX, 7);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8);
        SpritePosition sprite = new SpritePosition();
        sprite.enable(8, 16, 0xfe00);
        fixture.sprites[0] = sprite;

        assertEquals(266, fixture.renderer().predictMode3End(0));
    }

    @Test
    public void predictorIgnoresSpritesFullyOutsideViewport() {
        Fixture fixture = new Fixture(false, false);
        fixture.lcdc.set(0x93); // BG + OBJ
        fixture.registers.put(SCX, 3);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8); // one visible sprite at x=0
        SpritePosition visible = new SpritePosition();
        visible.enable(8, 16, 0xfe00);
        fixture.sprites[0] = visible;

        fixture.oam.setByte(0xfe04, 16);
        fixture.oam.setByte(0xfe05, 0); // x=0: the whole 8-pixel sprite is left of the viewport
        SpritePosition leftClipped = new SpritePosition();
        leftClipped.enable(0, 16, 0xfe04);
        fixture.sprites[1] = leftClipped;

        fixture.oam.setByte(0xfe08, 16);
        fixture.oam.setByte(0xfe09, 168); // x=168: the whole sprite is right of the viewport
        SpritePosition rightClipped = new SpritePosition();
        rightClipped.enable(168, 16, 0xfe08);
        fixture.sprites[2] = rightClipped;

        int visibleOnly = fixture.renderer().predictMode3End(0);
        assertEquals(260, visibleOnly);
    }

    @Test
    public void nativeCgbTileRunsMatchWrappedGenericRendererAcrossCoherentLines() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        PerformanceScanlineRenderer nativeRenderer = fixture.renderer();
        PerformanceScanlineRenderer genericRenderer = fixture.renderer(true);
        Random random = new Random(0x18a1f00dL);
        int[] nativePixels = new int[160];
        int[] genericPixels = new int[160];

        for (int line = 0; line < 320; line++) {
            int lcdc = 0x80
                    | (random.nextBoolean() ? 0x01 : 0)
                    | (random.nextBoolean() ? 0x02 : 0)
                    | (random.nextBoolean() ? 0x04 : 0)
                    | (random.nextBoolean() ? 0x08 : 0)
                    | (random.nextBoolean() ? 0x10 : 0)
                    | (random.nextBoolean() ? 0x20 : 0)
                    | (random.nextBoolean() ? 0x40 : 0);
            fixture.lcdc.set(lcdc);
            fixture.registers.put(SCX, random.nextInt(256));
            fixture.registers.put(SCY, random.nextInt(256));
            fixture.registers.put(WY, random.nextInt(256));
            fixture.registers.put(WX, random.nextInt(256));
            for (int i = 0; i < fixture.vram0.getSpace().length; i++) {
                fixture.vram0.getSpace()[i] = random.nextInt(256);
                fixture.vram1.getSpace()[i] = random.nextInt(256);
            }
            for (int palette = 0; palette < 8; palette++) {
                for (int color = 0; color < 4; color++) {
                    fixture.bgPalette.getPalette(palette)[color] = random.nextInt(0x10000);
                    fixture.oamPalette.getPalette(palette)[color] = random.nextInt(0x10000);
                }
            }
            fixture.configureRandomSprites(random);

            int windowLine = random.nextBoolean() ? -1 : random.nextInt(256);
            nativeRenderer.renderLine(line & 0xff, windowLine, nativePixels);
            genericRenderer.renderLine(line & 0xff, windowLine, genericPixels);
            assertArrayEquals("line " + line, genericPixels, nativePixels);
        }
    }

    @Test
    public void nativeCgbPreservesFullWidthSixteenBitBgAndObjectColors() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        fixture.lcdc.set(0x93);
        fixture.fillTile(0, 0xff, 0x00);
        fixture.fillTile(1, 0xff, 0x00);
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, 0);
        }
        fixture.bgPalette.getPalette(0)[1] = 0x8001;
        fixture.oamPalette.getPalette(0)[1] = 0xffff;
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8);
        fixture.oam.setByte(0xfe02, 1);
        fixture.oam.setByte(0xfe03, 0);
        fixture.sprites[0].enable(8, 16, 0xfe00);

        int[] pixels = new int[160];
        fixture.renderer().renderLine(0, -1, pixels);

        for (int i = 0; i < pixels.length; i++) {
            assertEquals(i < 8 ? 0xffff : 0x8001, pixels[i]);
        }
    }

    @Test
    public void nativeCgbSpritePriorityMatchesBackgroundPriorityAndLcdc0TruthTable() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        fixture.fillTile(0, 0xff, 0x00);
        fixture.fillTile(1, 0xff, 0x00);
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, 0);
        }
        fixture.bgPalette.getPalette(0)[1] = 0x1111;
        fixture.oamPalette.getPalette(0)[1] = 0x2222;
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8);
        fixture.oam.setByte(0xfe02, 1);
        fixture.sprites[0].enable(8, 16, 0xfe00);
        int[] pixels = new int[160];

        fixture.lcdc.set(0x93);
        fixture.oam.setByte(0xfe03, 0);
        fixture.vram1.setByte(0x9800, 0);
        fixture.renderer().renderLine(0, -1, pixels);
        assertEquals(0x2222, pixels[0]);

        fixture.vram1.setByte(0x9800, 0x80);
        fixture.renderer().renderLine(0, -1, pixels);
        assertEquals(0x1111, pixels[0]);

        fixture.vram1.setByte(0x9800, 0);
        fixture.oam.setByte(0xfe03, 0x80);
        fixture.renderer().renderLine(0, -1, pixels);
        assertEquals(0x1111, pixels[0]);

        fixture.lcdc.set(0x92); // Native CGB keeps BG pixels, but OBJ wins when LCDC.0 is clear.
        fixture.vram1.setByte(0x9800, 0x80);
        fixture.renderer().renderLine(0, -1, pixels);
        assertEquals(0x2222, pixels[0]);

        fixture.vram0.setByte(0x8000, 0x00);
        fixture.vram0.setByte(0x8001, 0x00);
        fixture.lcdc.set(0x93);
        fixture.renderer().renderLine(0, -1, pixels);
        assertEquals(0x2222, pixels[0]);
    }

    @Test
    public void nativeCgbOverlappingSpritesUseLowestOamAddress() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        fixture.lcdc.set(0x93);
        fixture.fillTile(0, 0x00, 0x00);
        fixture.fillTile(1, 0xff, 0x00);
        fixture.fillTile(2, 0xff, 0x00);
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, 0);
        }
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8);
        fixture.oam.setByte(0xfe02, 1);
        fixture.oam.setByte(0xfe03, 0);
        fixture.oam.setByte(0xfe04, 16);
        fixture.oam.setByte(0xfe05, 8);
        fixture.oam.setByte(0xfe06, 2);
        fixture.oam.setByte(0xfe07, 1);
        // Deliberately pass the selected entries in reverse order. The native overlay must use
        // OAM addresses, not selection-array order, to choose the winning non-transparent pixel.
        fixture.sprites[0].enable(8, 16, 0xfe04);
        fixture.sprites[1].enable(8, 16, 0xfe00);
        fixture.oamPalette.getPalette(0)[1] = 0x1111;
        fixture.oamPalette.getPalette(1)[1] = 0x2222;
        int[] pixels = new int[160];

        fixture.renderer().renderLine(0, -1, pixels);

        assertEquals(0x1111, pixels[0]);
    }

    @Test
    public void nativeCgbTransparentLowerOamSpriteFallsThroughToOpaqueHigherSprite() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        fixture.lcdc.set(0x93);
        fixture.fillTile(0, 0x00, 0x00);
        fixture.fillTile(1, 0x00, 0x00); // lower OAM sprite is transparent
        fixture.fillTile(2, 0xff, 0x00); // higher OAM sprite is raw color 1
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, 0);
        }
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8);
        fixture.oam.setByte(0xfe02, 1);
        fixture.oam.setByte(0xfe03, 0);
        fixture.oam.setByte(0xfe04, 16);
        fixture.oam.setByte(0xfe05, 8);
        fixture.oam.setByte(0xfe06, 2);
        fixture.oam.setByte(0xfe07, 0);
        fixture.sprites[0].enable(8, 16, 0xfe00);
        fixture.sprites[1].enable(8, 16, 0xfe04);
        fixture.oamPalette.getPalette(0)[1] = 0x2222;
        int[] pixels = new int[160];

        fixture.renderer().renderLine(0, -1, pixels);

        assertEquals(0x2222, pixels[0]);
    }

    @Test
    public void wrappedMemoryAndPalettesForceGenericPathWithoutChangingPixels() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        fixture.lcdc.set(0xf3);
        fixture.registers.put(SCX, 255);
        fixture.registers.put(SCY, 251);
        fixture.registers.put(WY, 1);
        fixture.registers.put(WX, 166);
        fixture.fillTile(0, 0x5a, 0xa5);
        fixture.fillTile(1, 0xa5, 0x5a);
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, tile & 1);
            fixture.vram1.setByte(0x9800 + tile, (tile & 7) | (tile << 3 & 0x78));
        }
        for (int i = 0; i < 8; i++) {
            Arrays.fill(fixture.bgPalette.getPalette(i), 0x3000 + i);
            Arrays.fill(fixture.oamPalette.getPalette(i), 0x4000 + i);
        }
        fixture.configureRandomSprites(new Random(0x18));
        int[] nativePixels = new int[160];
        int[] genericPixels = new int[160];
        fixture.renderer().renderLine(42, 19, nativePixels);
        fixture.renderer(true).renderLine(42, 19, genericPixels);
        assertArrayEquals(genericPixels, nativePixels);
    }

    @Test
    public void compatibilityModeFallsBackAndCanSwitchBackToNative() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        fixture.lcdc.set(0x91);
        fixture.fillTile(0, 0xff, 0x00);
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, 0);
        }
        fixture.bgPalette.getPalette(0)[1] = 0x7654;
        fixture.bgPalette.getPalette(0)[3] = 0x7654;
        fixture.registers.put(BGP, 0x0c);
        PerformanceScanlineRenderer renderer = fixture.renderer();
        int[] nativePixels = new int[160];
        int[] compatPixels = new int[160];
        renderer.renderLine(0, -1, nativePixels);
        renderer.setDmgCompat(true);
        renderer.renderLine(0, -1, compatPixels);
        assertEquals(0x7654, nativePixels[0]);
        assertEquals(0x7654, compatPixels[0]);
        fixture.bgPalette.getPalette(0)[3] = 0x1234;
        renderer.renderLine(0, -1, compatPixels);
        assertEquals(0x1234, compatPixels[0]);
        renderer.setDmgCompat(false);
        renderer.renderLine(0, -1, nativePixels);
        assertEquals(0x7654, nativePixels[0]);
    }

    @Test
    public void nativeAliasesAndPaletteRowsRemainLiveAfterConstructionAndRestore() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        fixture.lcdc.set(0x91);
        fixture.fillTile(0, 0xff, 0x00);
        fixture.fillTile(1, 0x00, 0xff);
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, 0);
        }
        fixture.bgPalette.getPalette(0)[1] = 0x1111;
        fixture.bgPalette.getPalette(0)[2] = 0x2222;
        PerformanceScanlineRenderer renderer = fixture.renderer();
        ComponentState<Ram> vramState = fixture.vram0.captureState();
        ComponentState<ColorPalette> bgPaletteState = fixture.bgPalette.captureState();
        ComponentState<ColorPalette> oamPaletteState = fixture.oamPalette.captureState();
        int[] first = new int[160];
        int[] changedVram = new int[160];
        int[] changedPalette = new int[160];
        int[] restored = new int[160];
        renderer.renderLine(0, -1, first);

        // Mutate the selected tile map after construction. The cached native alias must observe
        // the new tile and produce its distinct raw color.
        fixture.vram0.setByte(0x9800, 1);
        renderer.renderLine(0, -1, changedVram);
        assertEquals(0x2222, changedVram[0]);
        assertNotEquals(first[0], changedVram[0]);

        // Mutate both palette components through their live rows. The output must observe the BG
        // row, while the OAM row is restored below as an independent capture/restore check.
        fixture.bgPalette.getPalette(0)[2] = 0x3333;
        fixture.oamPalette.getPalette(0)[0] = 0x4444;
        renderer.renderLine(0, -1, changedPalette);
        assertEquals(0x3333, changedPalette[0]);

        fixture.vram0.restoreState(vramState);
        fixture.bgPalette.restoreState(bgPaletteState);
        fixture.oamPalette.restoreState(oamPaletteState);
        renderer.renderLine(0, -1, restored);

        assertEquals(0x1111, first[0]);
        assertArrayEquals(first, restored);
    }

    @Test
    public void nonCanonicalVramShapeUsesGenericAddressSpaceReads() {
        Fixture fixture = new Fixture(true, false);
        fixture.lcdc.setGbc(true);
        fixture.registers.setGbc(true);
        fixture.lcdc.set(0x91);
        fixture.fillTile(0, 0xff, 0x00);
        for (int tile = 0; tile < 32; tile++) {
            fixture.vram0.setByte(0x9800 + tile, 0);
            fixture.vram1.setByte(0x9800 + tile, 0);
        }
        fixture.bgPalette.getPalette(0)[1] = 0x1234;

        Ram shifted0 = new Ram(0x7fff, 0x2000); // accepts neither exact endpoint shape
        Ram shifted1 = new Ram(0x7fff, 0x2000);
        copyRam(fixture.vram0, shifted0, 0x8000, 0x9ffe);
        copyRam(fixture.vram1, shifted1, 0x8000, 0x9ffe);
        PerformanceScanlineRenderer shifted = new PerformanceScanlineRenderer(
                shifted0, shifted1, fixture.oam, fixture.lcdc, fixture.registers,
                fixture.bgPalette, fixture.oamPalette, true, false, fixture.sprites);
        int[] shiftedPixels = new int[160];
        shifted.renderLine(0, -1, shiftedPixels);
        assertEquals(0x1234, shiftedPixels[0]);
    }

    private static void copyRam(Ram source, Ram target, int first, int last) {
        for (int address = first; address <= last; address++) {
            target.setByte(address, source.getByte(address));
        }
    }

    private static final class Fixture {
        private final Ram vram0 = new Ram(0x8000, 0x2000);
        private final Ram vram1;
        private final Ram oam = new Ram(0xfe00, 0xa0);
        private final Lcdc lcdc = new Lcdc();
        private final GpuRegisterValues registers = new GpuRegisterValues();
        private final ColorPalette bgPalette = new ColorPalette(0xff68);
        private final ColorPalette oamPalette = new ColorPalette(0xff6a);
        private final SpritePosition[] sprites = new SpritePosition[10];
        private final boolean gbc;
        private final boolean dmgCompat;

        private Fixture(boolean gbc, boolean dmgCompat) {
            this.gbc = gbc;
            this.dmgCompat = dmgCompat;
            this.vram1 = gbc ? new Ram(0x8000, 0x2000) : null;
            for (int i = 0; i < sprites.length; i++) {
                sprites[i] = new SpritePosition();
            }
            registers.put(SCX, 0);
            registers.put(SCY, 0);
        }

        private PerformanceScanlineRenderer renderer() {
            return renderer(false);
        }

        private PerformanceScanlineRenderer renderer(boolean wrapped) {
            return new PerformanceScanlineRenderer(
                    wrapped ? new ForwardingAddressSpace(vram0) : vram0,
                    vram1 == null ? null : wrapped ? new ForwardingAddressSpace(vram1) : vram1,
                    wrapped ? new ForwardingAddressSpace(oam) : oam,
                    lcdc, registers,
                    wrapped ? new WrappedColorPalette(bgPalette) : bgPalette,
                    wrapped ? new WrappedColorPalette(oamPalette) : oamPalette,
                    gbc, dmgCompat, sprites);
        }

        private void configureRandomSprites(Random random) {
            lcdc.set(lcdc.get() | 0x02);
            for (int i = 0; i < sprites.length; i++) {
                int address = 0xfe00 + i * 4;
                if (random.nextBoolean()) {
                    int x = random.nextInt(176);
                    int y = random.nextInt(176);
                    int attributes = random.nextInt(256);
                    oam.setByte(address, y);
                    oam.setByte(address + 1, x);
                    oam.setByte(address + 2, random.nextInt(256));
                    oam.setByte(address + 3, attributes);
                    sprites[i].enable(x, y, address);
                } else {
                    sprites[i].disable();
                }
            }
        }

        private void fillTile(int tile, int low, int high) {
            for (int row = 0; row < 8; row++) {
                vram0.setByte(0x8000 + tile * 16 + row * 2, low);
                vram0.setByte(0x8001 + tile * 16 + row * 2, high);
            }
        }
    }

    private static final class ForwardingAddressSpace implements AddressSpace {
        private final AddressSpace delegate;

        private ForwardingAddressSpace(AddressSpace delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean accepts(int address) {
            return delegate.accepts(address);
        }

        @Override
        public void setByte(int address, int value) {
            delegate.setByte(address, value);
        }

        @Override
        public int getByte(int address) {
            return delegate.getByte(address);
        }
    }

    private static final class WrappedColorPalette extends ColorPalette {
        private final ColorPalette delegate;

        private WrappedColorPalette(ColorPalette delegate) {
            super(0xff68);
            this.delegate = delegate;
        }

        @Override
        public boolean accepts(int address) {
            return delegate.accepts(address);
        }

        @Override
        public void setByte(int address, int value) {
            delegate.setByte(address, value);
        }

        @Override
        public int getByte(int address) {
            return delegate.getByte(address);
        }

        @Override
        public int[] getPalette(int index) {
            return delegate.getPalette(index);
        }
    }
}
