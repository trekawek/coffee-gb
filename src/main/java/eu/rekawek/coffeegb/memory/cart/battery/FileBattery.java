package eu.rekawek.coffeegb.memory.cart.battery;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FileBattery implements Battery {

    private static final Logger LOG = LoggerFactory.getLogger(FileBattery.class);

    private final File saveFile;

    public FileBattery(File parent, String baseName) {
        this.saveFile = new File(parent, baseName + ".sav");
    }

    @Override
    public void loadRam(int[] ram) {
        loadRamWithClock(ram, null);
    }

    @Override
    public void saveRam(int[] ram) {
        saveRamWithClock(ram, null);
    }

    @Override
    public void loadRamWithClock(int[] ram, long[] clockData) {
        if (!saveFile.exists()) {
            return;
        }
        long saveLength = saveFile.length();
        saveLength = saveLength - (saveLength % 0x2000);
        try (InputStream is = new FileInputStream(saveFile)) {
            loadRam(ram, is, saveLength);
            if (clockData != null) {
                loadClock(clockData, is);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveRamWithClock(int[] ram, long[] clockData) {
        try (OutputStream os = new FileOutputStream(saveFile)) {
            saveRam(ram, os);
            if (clockData != null) {
                saveClock(clockData, os);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadClock(long[] clockData, InputStream is) throws IOException {
        byte[] byteBuff = new byte[4 * clockData.length];
        IOUtils.read(is, byteBuff);
        ByteBuffer buff = ByteBuffer.wrap(byteBuff);
        buff.order(ByteOrder.LITTLE_ENDIAN);
        int i = 0;
        while (buff.hasRemaining()) {
            clockData[i++] = buff.getInt() & 0xffffffff;
        }
    }

    private void saveClock(long[] clockData, OutputStream os) throws IOException {
        byte[] byteBuff = new byte[4 * clockData.length];
        ByteBuffer buff = ByteBuffer.wrap(byteBuff);
        buff.order(ByteOrder.LITTLE_ENDIAN);
        for (long d : clockData) {
            buff.putInt((int) d);
        }
        IOUtils.write(byteBuff, os);
    }

    private void loadRam(int[] ram, InputStream is, long length) throws IOException {
        byte[] buffer = new byte[ram.length];
        IOUtils.read(is, buffer, 0, Math.min((int) length, ram.length));
        for (int i = 0; i < ram.length; i++) {
            ram[i] = buffer[i] & 0xff;
        }
    }

    private void saveRam(int[] ram, OutputStream os) throws IOException {
        byte[] buffer = new byte[ram.length];
        for (int i = 0; i < ram.length; i++) {
            buffer[i] = (byte) (ram[i]);
        }
        IOUtils.write(buffer, os);
    }
}
