package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class Rom implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Rom.class);

    private final int[] space;

    private final int offset;

    public Rom(File file, int offset) throws IOException {
        this.space = loadFile(file);
        this.offset = offset;
    }

    public Rom(int[] space, int offset) {
        this.space = space;
        this.offset = offset;
    }

    @Override
    public void setByte(int address, int value) {
        LOG.warn("Can't write {} to ROM {}", value, address);
    }

    @Override
    public int getByte(int address) {
        if (offset > address) {
            LOG.warn("Address {} < offset {}", address, offset);
            return 0;
        }
        int position = address - offset;
        if (position >= space.length) {
            LOG.warn("Address {} out of ROM space {}", address, offset + space.length);
            return 0;
        }
        return space[position] & 0xff;
    }

    private static int[] loadFile(File file) throws IOException {
        byte[] byteArray;
        try (InputStream is = new FileInputStream(file)) {
            byteArray = IOUtils.toByteArray(is);
        }
        int[] intArray = new int[byteArray.length];
        for (int i = 0; i < byteArray.length; i++) {
            intArray[i] = byteArray[i] & 0xff;
        }
        return intArray;
    }
}
