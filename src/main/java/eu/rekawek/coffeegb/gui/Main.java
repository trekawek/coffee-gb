package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.GameboyOptions;
import eu.rekawek.coffeegb.cpu.SpeedMode;
import eu.rekawek.coffeegb.memory.cart.Cartridge;

import java.io.File;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public class Main {

    private static final int SCALE = 2;

    private final Cartridge rom;

    private final AudioSystemSoundOutput sound;

    private final SwingDisplay display;

    private final SwingController controller;

    private final SystemOutSerialEndpoint serialEndpoint;

    private final SpeedMode speedMode;

    private final Gameboy gameboy;

    private JFrame mainWindow;

    public Main(String romPath) throws IOException {
        rom = new Cartridge(new File(romPath));
        speedMode = new SpeedMode();
        sound = new AudioSystemSoundOutput();
        display = new SwingDisplay(SCALE);
        display.setPreferredSize(new Dimension(160 * SCALE, 144 * SCALE));
        serialEndpoint = new SystemOutSerialEndpoint();
        controller = new SwingController();
        gameboy = new Gameboy(new GameboyOptions(), rom, display, controller, sound, serialEndpoint);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.java2d.opengl", "true");
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        Main main = new Main(args[0]);
        javax.swing.SwingUtilities.invokeLater(() -> main.start());
    }

    private void start() {
        mainWindow = new JFrame("Coffee GB");
        mainWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mainWindow.setLocationRelativeTo(null);

        mainWindow.setContentPane(display);
        mainWindow.setResizable(false);
        mainWindow.setVisible(true);
        mainWindow.pack();

        mainWindow.addKeyListener(controller);

        new Thread(display).start();
        new Thread(gameboy).start();
    }

    private void stop() {
        display.stop();
        gameboy.stop();
        mainWindow.dispose();
    }
}
