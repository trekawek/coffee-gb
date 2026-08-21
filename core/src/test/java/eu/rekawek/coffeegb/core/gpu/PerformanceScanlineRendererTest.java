package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.BGP;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.OBP0;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.SCX;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.SCY;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.WX;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.WY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
            return new PerformanceScanlineRenderer(
                    vram0, vram1, oam, lcdc, registers, bgPalette, oamPalette,
                    gbc, dmgCompat, sprites);
        }

        private void fillTile(int tile, int low, int high) {
            for (int row = 0; row < 8; row++) {
                vram0.setByte(0x8000 + tile * 16 + row * 2, low);
                vram0.setByte(0x8001 + tile * 16 + row * 2, high);
            }
        }
    }
}
