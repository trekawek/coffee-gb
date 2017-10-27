package eu.rekawek.coffeegb.debug.command.ppu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.debug.Command;
import eu.rekawek.coffeegb.debug.CommandPattern;
import eu.rekawek.coffeegb.debug.CommandPattern.ParsedCommandLine;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.gpu.GpuRegister;
import eu.rekawek.coffeegb.gpu.Lcdc;
import eu.rekawek.coffeegb.gpu.TileAttributes;
import eu.rekawek.coffeegb.gui.SwingDisplay;
import eu.rekawek.coffeegb.memory.MemoryRegisters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

import static eu.rekawek.coffeegb.cpu.BitUtils.toSigned;
import static eu.rekawek.coffeegb.gpu.Fetcher.zip;

public class ShowBackground implements Command {

    private static final Logger LOG = LoggerFactory.getLogger(ShowBackground.class);

    public enum Type {
        WINDOW, BACKGROUND;
    }

    private static final CommandPattern PATTERN_BACKGROUND = CommandPattern.Builder
            .create("ppu show background")
            .withDescription("display the background tiles")
            .build();

    private static final CommandPattern PATTERN_WINDOW = CommandPattern.Builder
            .create("ppu show window")
            .withDescription("display the window tiles")
            .build();


    private final Gameboy gameboy;

    private volatile boolean windowPresent;

    private final Type type;

    public ShowBackground(Gameboy gameboy, Type type) {
        this.gameboy = gameboy;
        this.type = type;
    }

    @Override
    public CommandPattern getPattern() {
        return type == Type.WINDOW ? PATTERN_WINDOW : PATTERN_BACKGROUND;
    }

    @Override
    public void run(ParsedCommandLine commandLine) {
        if (windowPresent) {
            System.out.println("Window already present");
            return;
        }

        SwingUtilities.invokeLater(() -> {
            BackgroundTiles panel = new BackgroundTiles(gameboy.getGpu());
            panel.setPreferredSize(new Dimension(272 * 2, 272 * 2));

            new Thread(panel).start();

            Runnable panelTick = panel::tick;

            JFrame mainWindow = new JFrame(type == Type.BACKGROUND ? "Background tiles" : "Window tiles");
            mainWindow.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            mainWindow.setLocationRelativeTo(null);
            mainWindow.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    super.windowClosed(e);
                    gameboy.unregisterTickListener(panelTick);
                    panel.doStop = true;
                    synchronized (panel) {
                        panel.notify();
                    }
                    windowPresent = false;
                }
            });

            mainWindow.setContentPane(panel);
            mainWindow.setResizable(false);
            mainWindow.setVisible(true);
            mainWindow.pack();

            gameboy.registerTickListener(panelTick);

            windowPresent = true;
        });
    }

    public class BackgroundTiles extends JPanel implements Runnable {

        private boolean doRefresh;

        private boolean doStop;

        private final BufferedImage img;

        private final GraphicsConfiguration gfxConfig;

        private final Gpu gpu;

        private Gpu.Mode lastMode;

        public BackgroundTiles(Gpu gpu) {
            super();
            gfxConfig = GraphicsEnvironment.
                    getLocalGraphicsEnvironment().getDefaultScreenDevice().
                    getDefaultConfiguration();
            img = gfxConfig.createCompatibleImage(272 * 2, 272 * 2);
            this.gpu = gpu;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.drawImage(img, 0, 0, 272 * 2, 272 * 2, null);
            g2d.dispose();
        }

        private void drawBackground() {
            Graphics2D g2d = img.createGraphics();
            g2d.clearRect(0, 0, 272 * 2, 272 * 2);

            Lcdc lcdc = gpu.getLcdc();
            AddressSpace videoRam0 = gpu.getVideoRam0();
            AddressSpace videoRam1 = gpu.getVideoRam1();
            MemoryRegisters reg = gpu.getRegisters();

            int tileMap = type == Type.BACKGROUND ? lcdc.getBgTileMapDisplay() : lcdc.getWindowTileMapDisplay();
            int tileData = lcdc.getBgWindowTileData();
            int dmgPalette = reg.get(GpuRegister.BGP);

            int[][] tile = new int[8][8];
            for (int x = 0; x < 32; x++) {
                for (int y = 0; y < 32; y++) {
                    int tileId = videoRam0.getByte(tileMap + x + 32 * y);
                    TileAttributes attr;
                    if (videoRam1 == null) {
                        attr = TileAttributes.valueOf(0);
                    } else {
                        attr = TileAttributes.valueOf(videoRam1.getByte(tileMap + x + 32 * y));
                    }
                    final int tileAddress;
                    if (lcdc.isBgWindowTileDataSigned()) {
                        tileAddress = tileData + toSigned(tileId) * 0x10;
                    } else {
                        tileAddress = tileData + tileId * 0x10;
                    }
                    for (int i = 0; i < 16; i += 2) {
                        AddressSpace bank = attr.getBank() == 0 ? videoRam0 : videoRam1;
                        int b1 = bank.getByte(tileAddress + i);
                        int b2 = bank.getByte(tileAddress + i + 1);
                        zip(b1, b2, attr.isXflip(), tile[i/2]);
                    }
                    if (attr.isYflip()) {
                        for (int i = 0; i < 8; i++) {
                            int[] tmp = tile[i];
                            tile[i] = tile[7 - i];
                            tile[7 - i] = tmp;
                        }
                    }
                    int[] palette = new int[4];
                    if (gpu.isGbc()) {
                        int[] gbcPalette = gpu.getBgPalette().getPalette(attr.getColorPaletteIndex());
                        for (int i = 0; i < palette.length; i++) {
                            palette[i] = SwingDisplay.translateGbcRgb(gbcPalette[i]);
                        }
                    } else {
                        for (int i = 0; i < palette.length; i++) {
                            palette[i] = SwingDisplay.COLORS[0b11 & (dmgPalette >> (i * 2))];
                        }
                    }
                    drawTile(g2d, tile, palette, x, y);
                }
            }
            if (type == Type.BACKGROUND) {
                drawScrollFrame(g2d, reg.get(GpuRegister.SCX), reg.get(GpuRegister.SCY));
            }
        }

        private void drawTile(Graphics2D g2d, int[][] tile, int[] palette, int x, int y) {
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 8; j++) {
                    g2d.setColor(new Color(palette[tile[j][i]]));
                    g2d.fillRect(x * 17 + i * 2, y * 17 + j * 2, 2, 2);
                }
            }
        }

        private void drawScrollFrame(Graphics2D g2d, int scx, int scy) {
            g2d.setColor(Color.RED);
            drawHorizontalLine(g2d, scx, scy);
            drawVerticalLine(g2d, scx, scy);
            drawVerticalLine(g2d, (scx + 160) % 256, scy);
            drawHorizontalLine(g2d, scx, (scy + 144) % 256);
        }

        private void drawHorizontalLine(Graphics2D g2d, int x1, int y1) {
            if (x1 + 160 < 256) {
                drawLine(g2d, x1, y1, x1 + 160, y1);
            } else {
                drawLine(g2d, x1, y1, 255, y1);
                drawLine(g2d, 0, y1, x1 + 160 - 256, y1);
            }
        }

        private void drawVerticalLine(Graphics2D g2d, int x1, int y1) {
            if (y1 + 144 < 256) {
                drawLine(g2d, x1, y1, x1, y1 + 144);
            } else {
                drawLine(g2d, x1, y1, x1, 255);
                drawLine(g2d, x1, 0, x1 , y1+ 144 - 256);
            }
        }

        private void drawLine(Graphics2D g2d, int x1, int y1, int x2, int y2) {
            g2d.drawLine(translate(x1), translate(y1), translate(x2), translate(y2));
            if (x1 == x2) {
                g2d.drawLine(translate(x1) + 1, translate(y1), translate(x2) + 1, translate(y2));
            }
            if (y1 == y2) {
                g2d.drawLine(translate(x1), translate(y1) + 1, translate(x2), translate(y2) + 1);
            }
        }

        private int translate(int point) {
            return point / 8 * 17 + (point % 8) * 2;
        }

        @Override
        public void run() {
            while (!doStop) {
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        LOG.error("Can't refresh background window", e);
                        return;
                    }
                }

                if (doRefresh) {
                    doRefresh = false;
                    validate();
                    repaint();
                }
            }
        }

        public void tick() {
            Gpu.Mode currentMode = gpu.getMode();
            if (currentMode != lastMode && currentMode == Gpu.Mode.VBlank) {
                drawBackground();
                doRefresh = true;
                synchronized (this) {
                    notify();
                }
            }
            lastMode = currentMode;
        }
    }

}