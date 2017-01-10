package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.memory.Rom;

import java.io.File;
import javax.swing.*;
import java.awt.*;

public class Main {

    static final int SCALE = 2;

    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(() -> createAndShowGUI());
    }

    private static void createAndShowGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            JFrame mainWindow = new JFrame("Coffee GB");
            mainWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            mainWindow.setLocationRelativeTo(null);

            LcdDisplay display = new LcdDisplay();
            display.setPreferredSize(new Dimension(160 * SCALE, 144 * SCALE));

            mainWindow.setContentPane(display);
            mainWindow.setResizable(false);
            mainWindow.setVisible(true);
            mainWindow.pack();

            final Rom rom = new Rom(new File("src/test/resources/dr-mario.gb"), 0);
            new Thread(() -> new Gameboy(rom, display).run()).start();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
