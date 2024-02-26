package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.CartridgeOptions;
import eu.rekawek.coffeegb.debug.Console;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialEndpoint;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

public class SwingGui {

    private static final Logger LOG = LoggerFactory.getLogger(SwingGui.class);

    private static final File PROPERTIES_FILE = new File(new File(System.getProperty("user.home")), ".coffeegb.properties");

    private final RecentRoms recentRoms;

    private final SwingDisplay display;

    private final SwingController controller;

    private final AudioSystemSoundOutput sound;

    private final CartridgeOptions options;

    private File currentRom;

    private final Properties properties;

    private final Console console;

    private JFrame mainWindow;

    private JCheckBoxMenuItem pauseGame;

    private JMenuItem resetGame;

    private Cartridge cart;

    private Gameboy gameboy;

    private boolean isRunning;

    private Cartridge.GameboyType type;

    public SwingGui(CartridgeOptions options, boolean debug, File initialRom) throws IOException {
        this.options = options;
        this.currentRom = initialRom;
        properties = loadProperties();
        recentRoms = new RecentRoms(properties);

        int displayScale = Integer.parseInt(properties.getProperty("display.scale", "2"));
        boolean displayGrayscale = Boolean.parseBoolean(properties.getProperty("display.grayscale", "false"));
        boolean soundEnabled = Boolean.parseBoolean(properties.getProperty("sound.enabled", "true"));
        type = Cartridge.GameboyType.valueOf(properties.getProperty("gameBoy.type", Cartridge.GameboyType.AUTOMATIC.name()));

        display = new SwingDisplay(displayScale, displayGrayscale);
        controller = new SwingController(properties);
        sound = new AudioSystemSoundOutput();
        sound.setEnabled(soundEnabled);
        console = debug ? new Console() : null;
    }

    public void run() throws Exception {
        System.setProperty("apple.awt.application.name", "Coffee GB");
        System.setProperty("sun.java2d.opengl", "true");
        SwingUtilities.invokeLater(this::startGui);
    }

    public void startEmulation(File rom) {
        Cartridge newCart;
        try {
            newCart = loadRom(rom);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(mainWindow, e.getMessage(), "Can't load ROM", JOptionPane.ERROR_MESSAGE);
            return;
        }
        stopEmulation();
        cart = newCart;
        mainWindow.setTitle("Coffee GB: " + cart.getTitle());
        gameboy = new Gameboy(cart, display, controller, sound, SerialEndpoint.NULL_ENDPOINT, console);
        gameboy.registerTickListener(new TimingTicker());
        if (console != null) {
            console.setGameboy(gameboy);
        }
        new Thread(display).start();
        new Thread(sound).start();
        new Thread(gameboy).start();
        isRunning = true;
        pauseGame.setEnabled(true);
        pauseGame.setState(false);
        resetGame.setEnabled(true);
        currentRom = rom;
    }

    private void stopEmulation() {
        if (!isRunning) {
            return;
        }
        isRunning = false;
        if (gameboy != null) {
            gameboy.stop();
            gameboy = null;
        }
        if (cart != null) {
            cart.flushBattery();
            cart = null;
        }
        sound.stopThread();
        display.stop();
        if (console != null) {
            console.setGameboy(null);
        }
        pauseGame.setEnabled(false);
        pauseGame.setState(false);
        resetGame.setEnabled(false);
    }

    private Cartridge loadRom(File rom) throws IOException {
        return new Cartridge(rom, options.isSupportBatterySaves(), type, options.isUsingBootstrap());
    }

    private void startGui() {
        display.setScale(display.getScale());
        mainWindow = new JFrame("Coffee GB");

        final JFileChooser fc = new JFileChooser();
        if (properties.containsKey("rom_dir")) {
            fc.setCurrentDirectory(new File(properties.getProperty("rom_dir")));
        }

        JMenuBar menuBar = new JMenuBar();
        mainWindow.setJMenuBar(menuBar);

        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);

        JMenuItem load = new JMenuItem("Load ROM");
        fileMenu.add(load);

        JMenu recentRomsMenu = new JMenu("Recent ROMs");
        fileMenu.add(recentRomsMenu);

        load.addActionListener(actionEvent -> {
            int code = fc.showOpenDialog(load);
            if (code == JFileChooser.APPROVE_OPTION) {
                File rom = fc.getSelectedFile();
                properties.setProperty("rom_dir", rom.getParent());
                launchRom(recentRomsMenu, rom);
            }
        });

        updateRecentRoms(recentRomsMenu);

        JMenu gameMenu = new JMenu("Game");
        menuBar.add(gameMenu);

        pauseGame = new JCheckBoxMenuItem("Pause", false);
        pauseGame.setEnabled(false);
        pauseGame.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        gameMenu.add(pauseGame);
        pauseGame.addActionListener(actionEvent -> {
            if (gameboy != null) {
                gameboy.setPaused(pauseGame.getState());
            }
        });

        JMenu typeMenu = new JMenu("GameBoy type");
        gameMenu.add(typeMenu);

        for (Cartridge.GameboyType type : Cartridge.GameboyType.values()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(type.getLabel(), type == this.type);
            item.addActionListener(actionEvent -> {
                this.type = type;
                if (isRunning) {
                    startEmulation(currentRom);
                }
                uncheckAllBut(typeMenu, item);
                properties.setProperty("gameBoy.type", type.name());
                saveProperties();
            });
            typeMenu.add(item);
        }

        resetGame = new JMenuItem("Reset");
        gameMenu.add(resetGame);
        resetGame.addActionListener(actionEvent -> {
            if (currentRom != null) {
                startEmulation(currentRom);
            }
        });
        resetGame.setEnabled(false);

        JMenu screenMenu = new JMenu("Screen");
        menuBar.add(screenMenu);

        JMenu scale = new JMenu("Scale");
        screenMenu.add(scale);

        for (int s : Arrays.asList(1, 2, 4)) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(s + "x", s == display.getScale());
            item.addActionListener(actionEvent -> {
                display.setScale(s);
                mainWindow.pack();
                uncheckAllBut(scale, item);
                properties.setProperty("display.scale", String.valueOf(s));
                saveProperties();
            });
            scale.add(item);
        }

        JCheckBoxMenuItem grayscale = new JCheckBoxMenuItem("DMG grayscale", display.isGrayscale());
        screenMenu.add(grayscale);
        grayscale.addActionListener(actionEvent -> {
            display.setGrayscale(grayscale.getState());
            properties.setProperty("display.grayscale", String.valueOf(grayscale.getState()));
            saveProperties();
        });

        JMenu audioMenu = new JMenu("Audio");
        menuBar.add(audioMenu);

        JCheckBoxMenuItem enableSound = new JCheckBoxMenuItem("Enable", sound.isEnabled());
        audioMenu.add(enableSound);

        enableSound.addActionListener(actionEvent -> {
            sound.setEnabled(enableSound.getState());
            properties.setProperty("sound.enabled", String.valueOf(enableSound.getState()));
            saveProperties();
        });

        mainWindow.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        mainWindow.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent windowEvent) {
                stopGui();
            }
        });
        mainWindow.setLocationRelativeTo(null);

        mainWindow.setContentPane(display);
        mainWindow.setResizable(false);
        mainWindow.setVisible(true);
        mainWindow.pack();
        mainWindow.addKeyListener(controller);
        if (console != null) {
            new Thread(console).start();
        }
        if (currentRom != null) {
            startEmulation(currentRom);
        }
    }

    private void stopGui() {
        stopEmulation();
        if (console != null) {
            console.stop();
        }
        System.exit(0);
    }

    private static Properties loadProperties() throws IOException {
        Properties props = new Properties();
        if (PROPERTIES_FILE.exists()) {
            try (FileReader reader = new FileReader(PROPERTIES_FILE)) {
                props.load(reader);
            }
        }
        return props;
    }

    private void saveProperties() {
        try (FileWriter writer = new FileWriter(PROPERTIES_FILE)) {
            properties.store(writer, "");
        } catch (IOException e) {
            LOG.error("Can't store properties", e);
        }
    }

    private void launchRom(JMenu recentRomsMenu, File rom) {
        recentRoms.addRom(rom.getAbsolutePath());
        saveProperties();
        updateRecentRoms(recentRomsMenu);
        startEmulation(rom);
    }

    private void updateRecentRoms(JMenu recentRomsMenu) {
        recentRomsMenu.removeAll();
        for (String romPath : recentRoms.getRoms()) {
            File rom = new File(romPath);
            JMenuItem item = new JMenuItem(rom.getName());
            item.addActionListener(actionEvent -> {
                launchRom(recentRomsMenu, rom);
            });
            recentRomsMenu.add(item);
        }
    }

    private static void uncheckAllBut(JMenu parent, JCheckBoxMenuItem selectedItem) {
        for (int i = 0; i < parent.getItemCount(); i++) {
            JMenuItem item = parent.getItem(i);
            if (item == selectedItem) {
                continue;
            }
            ((JCheckBoxMenuItem) item).setState(false);
        }
    }

}
