package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.checkWordArgument;

public class Mmu implements AddressSpace, Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(Mmu.class);

    private static final AddressSpace VOID = new Void();

    private final List<AddressSpace> spaces = new ArrayList<>();

    private AddressSpace[] addressToSpace;

    public void addAddressSpace(AddressSpace space) {
        spaces.add(space);
    }

    public void indexSpaces() {
        addressToSpace = new AddressSpace[0x10000];
        for (int i = 0; i < addressToSpace.length; i++) {
            addressToSpace[i] = VOID;
            for (AddressSpace s : spaces) {
                if (s.accepts(i)) {
                    addressToSpace[i] = s;
                    break;
                }
            }
        }
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
        if (addressToSpace == null) {
            throw new IllegalStateException("Address spaces hasn't been indexed yet");
        }
        return addressToSpace[address];
    }

    private static class Void implements AddressSpace, Serializable {
        @Override
        public boolean accepts(int address) {
            return true;
        }

        @Override
        public void setByte(int address, int value) {
            if (address < 0 || address > 0xffff) {
                throw new IllegalArgumentException("Invalid address: " + Integer.toHexString(address));
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Writing value {} to void address {}", Integer.toHexString(value), Integer.toHexString(address));
            }
        }

        @Override
        public int getByte(int address) {
            if (address < 0 || address > 0xffff) {
                throw new IllegalArgumentException("Invalid address: " + Integer.toHexString(address));
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Reading value from void address {}", Integer.toHexString(address));
            }
            return 0xff;
        }
    }
}
