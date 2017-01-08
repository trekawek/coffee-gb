package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.Gameboy;
import org.apache.commons.io.IOUtils;

import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class Main {

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
            display.setSize(160 * 2, 144 * 2);

            mainWindow.setContentPane(display);
            mainWindow.setResizable(false);
            mainWindow.setVisible(true);
            mainWindow.setSize(160 * 2 + 10, 144 * 2 + 50);

            new Thread(() -> new Gameboy(getRom(), display).run()).start();
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int[] getRom() {
        byte[] rom = new byte[32768];
        try {
            IOUtils.read(new FileInputStream(new File("src/test/resources/dr-mario.gb")), rom);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        int[] r = new int[rom.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = rom[i] & 0xff;
        }
        return r;
    }

}
