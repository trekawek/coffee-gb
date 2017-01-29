package eu.rekawek.coffeegb.memory.cart.battery;

import com.google.common.base.Charsets;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

public class FileBattery implements Battery {

    private static final Logger LOG = LoggerFactory.getLogger(FileBattery.class);

    private final File saveFile;

    private final File clockFile;

    public FileBattery(File parent, String baseName) {
        this.saveFile = new File(parent, baseName + ".sav");
        this.clockFile = new File(parent, baseName + ".clk");
    }

    @Override
    public void loadRam(int[] ram) {
        if (!saveFile.exists()) {
            return;
        }
        byte[] buffer = new byte[ram.length];
        try (InputStream is = new FileInputStream(saveFile)) {
            IOUtils.read(is, buffer);
        } catch (IOException e) {
            LOG.info("Can't load battery save file", e);
        }
        for (int i = 0; i < ram.length; i++) {
            ram[i] = buffer[i];
        }
    }

    @Override
    public void saveRam(int[] ram) {
        byte[] buffer = new byte[ram.length];
        for (int i = 0; i < ram.length; i++) {
            buffer[i] = (byte) (ram[i]);
        }
        try (OutputStream os = new FileOutputStream(saveFile)) {
            IOUtils.write(buffer, os);
        } catch (IOException e) {
            LOG.info("Can't save to battery save file", e);
        }
    }

    @Override
    public long[] loadClock() {
        if (!clockFile.exists()) {
            return null;
        }
        try (InputStream is = new FileInputStream(clockFile)) {
            return IOUtils.readLines(is, Charsets.UTF_8).stream().mapToLong(Long::parseLong).toArray();
        } catch (IOException e) {
            LOG.info("Can't load clock data", e);
            return null;
        }
    }

    @Override
    public void saveClock(long[] clockData) {
        try (OutputStream os = new FileOutputStream(clockFile)) {
            IOUtils.writeLines(Arrays.stream(clockData).mapToObj(Long::toString).collect(Collectors.toList()), "\n", os, Charsets.UTF_8);
        } catch (IOException e) {
            LOG.info("Can't store clock data", e);
        }
    }
}
