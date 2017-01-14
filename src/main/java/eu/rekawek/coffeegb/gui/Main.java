package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.memory.cart.Cartridge;

import java.io.File;
import javax.swing.*;
import java.awt.*;

public class Main {

    private static final int SCALE = 2;

    public static void main(String[] args) {
        System.setProperty("sun.java2d.opengl", "true");
        javax.swing.SwingUtilities.invokeLater(() -> start(args[0]));
    }

    private static void start(String romPath) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            JFrame mainWindow = new JFrame("Coffee GB");
            mainWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            mainWindow.setLocationRelativeTo(null);

            LcdDisplay display = new LcdDisplay(SCALE);
            display.setPreferredSize(new Dimension(160 * SCALE, 144 * SCALE));

            mainWindow.setContentPane(display);
            mainWindow.setResizable(false);
            mainWindow.setVisible(true);
            mainWindow.pack();

            final SwingController controller = new SwingController();
            mainWindow.addKeyListener(controller);

            final Cartridge rom = new Cartridge(new File(romPath));

            new Thread(() -> new Gameboy(rom, display, controller).run()).start();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
