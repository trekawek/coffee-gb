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
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

public class ImageTestRunner {

    private final TestDisplay display;

    private final Gameboy gb;

    private final File imageFile;

    public ImageTestRunner(File romFile) throws IOException {
        GameboyOptions options = new GameboyOptions(romFile, Collections.singletonList("grayscale"),Collections.emptyList());
        Cartridge cart = new Cartridge(options);
        display = new TestDisplay();
        gb = new Gameboy(options, cart, display, Controller.NULL_CONTROLLER, SoundOutput.NULL_OUTPUT, SerialEndpoint.NULL_ENDPOINT);
        imageFile = new File(romFile.getParentFile(),romFile.getName().replace(".gb",".png"));
    }

    public TestResult runTest() throws Exception {
        new Thread(gb).start();
        Thread.sleep(2000);
        gb.stop();
        String errorMsg = "The screen does not correspond to the expected image: "+imageFile;
        return new TestResult(display.getRGB(), getExpectedRGB(),errorMsg);
    }

    private int[] getExpectedRGB() throws Exception {
        BufferedImage expectedImg = ImageIO.read(imageFile);
        int[] rgb = new int[Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT];
        for (int h = 0; h < Display.DISPLAY_HEIGHT; h++){
            for (int w = 0; w < Display.DISPLAY_WIDTH; w++) {
                rgb[h*Display.DISPLAY_WIDTH+w] = expectedImg.getRGB(w, h) & 0xffffff;
            }
        }
        return rgb;
    }

    public class TestDisplay implements Display {

        private /*static*/ final int[] COLORS = new int[]{0xFFFFFF,0xAAAAAA, 0x555555, 0x000000};

        private final int[] rgb;

        private int i=0;

        public TestDisplay() {
            super();
            rgb = new int[DISPLAY_WIDTH * DISPLAY_HEIGHT];
        }

        @Override
        public void putDmgPixel(int color) {
            rgb[i++] = COLORS[color];
            i = i % rgb.length;
        }

        @Override
        public void putColorPixel(int gbcRgb) {
            rgb[i++] = Display.translateGbcRgb(gbcRgb);
        }

        @Override
        public void requestRefresh() {
            i=0;
        }

        @Override
        public void waitForRefresh() {
        }

        @Override
        public void enableLcd() {
        }

        @Override
        public void disableLcd() {
        }

        public int[] getRGB(){
            return rgb;
        }
    }


    public static class TestResult {

        private final int[] resultRGB;

        private final int[] expectedRGB;

        private final String errorMessage;

        public TestResult(int[] resultRGB, int[] expectedRGB, String errorMessage) {
            this.resultRGB = resultRGB;
            this.expectedRGB = expectedRGB;
            this.errorMessage = errorMessage;
        }

        public int[] getResultRGB() {
            return resultRGB;
        }

        public int[] getExpectedRGB() {
            return expectedRGB;
        }

        public String getErrorMessage(){return errorMessage;};
    }


}
