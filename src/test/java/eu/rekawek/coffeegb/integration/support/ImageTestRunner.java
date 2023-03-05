package eu.rekawek.coffeegb.integration.support;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.GameboyOptions;
import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.Registers;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gui.SwingDisplay;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialEndpoint;
import eu.rekawek.coffeegb.sound.SoundOutput;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;

public class ImageTestRunner {

    private final SwingDisplay display;

    private final Gameboy gb;

    private final File imageFile;

    public ImageTestRunner(File romFile) throws IOException {
        GameboyOptions options = new GameboyOptions(romFile, Collections.singletonList("grayscale"),Collections.emptyList());
        Cartridge cart = new Cartridge(options);
        display = new SwingDisplay(1, options.isGrayscale());
        gb = new Gameboy(options, cart, display, Controller.NULL_CONTROLLER, SoundOutput.NULL_OUTPUT, SerialEndpoint.NULL_ENDPOINT);
        imageFile = new File(romFile.getParentFile(),romFile.getName().replace(".gb",".png"));
    }

    public boolean runTest() throws Exception {
        new Thread(display).start();
        new Thread(gb).start();
        Thread.sleep(2000);
        gb.stop();
        BufferedImage expectedImg = ImageIO.read(imageFile);
        boolean result = bufferedImagesEquals(expectedImg, display.getImg());
        if(!result){
            System.err.print("The screen does not correspond to the expected image: "+imageFile);
        }
        return result;
    }

    boolean bufferedImagesEquals(BufferedImage img1, BufferedImage img2) {
        if (img1.getWidth() == img2.getWidth() && img1.getHeight() == img2.getHeight()) {
            for (int x = 0; x < img1.getWidth(); x++) {
                for (int y = 0; y < img1.getHeight(); y++) {
                    if (img1.getRGB(x, y) != img2.getRGB(x, y))
                        return false;
                }
            }
        } else {
            return false;
        }
        return true;
    }



}
