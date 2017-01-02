package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rom implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Rom.class);

    private final int[] space;

    private final int offset;

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
        return space[position];
    }
}
