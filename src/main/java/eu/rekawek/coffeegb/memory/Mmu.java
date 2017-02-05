package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.checkWordArgument;

public class Mmu implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Mmu.class);

    private static final AddressSpace VOID = new AddressSpace() {
        @Override
        public boolean accepts(int address) {
            return true;
        }

        @Override
        public void setByte(int address, int value) {
            if (address < 0 || address > 0xffff) {
                throw new IllegalArgumentException("Invalid address: " + Integer.toHexString(address));
            }
            LOG.debug("Writing value {} to void address {}", Integer.toHexString(value), Integer.toHexString(address));
        }

        @Override
        public int getByte(int address) {
            if (address < 0 || address > 0xffff) {
                throw new IllegalArgumentException("Invalid address: " + Integer.toHexString(address));
            }
            LOG.debug("Reading value from void address {}", Integer.toHexString(address));
            return 0xff;
        }
    };

    private final List<AddressSpace> spaces = new ArrayList<>();

    public void addAddressSpace(AddressSpace space) {
        spaces.add(space);
    }

    @Override
    public boolean accepts(int address) {
        return true;
    }

    @Override
    public void setByte(int address, int value) {
        checkByteArgument("value", value);
        checkWordArgument("address", address);
        getSpace(address).setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        checkWordArgument("address", address);
        return getSpace(address).getByte(address);
    }

    private AddressSpace getSpace(int address) {
        for (AddressSpace s : spaces) {
            if (s.accepts(address)) {
                return s;
            }
        }
        return VOID;
    }

}
