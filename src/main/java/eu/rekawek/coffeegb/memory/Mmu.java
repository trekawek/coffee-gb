package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.checkWordArgument;

public class Mmu implements AddressSpace, Serializable, Originator<Mmu> {

  private static final Logger LOG = LoggerFactory.getLogger(Mmu.class);

  private static final AddressSpace VOID = new Void();

  private final List<AddressSpace> spaces = new ArrayList<>();

  private final Ram ramC000 = new Ram(0xc000, 0x1000);

  private final Ram ramD000 = new Ram(0xd000, 0x1000);

  private final Ram ramFF80 = new Ram(0xff80, 0x7f);

  private final GbcRam gbcRam = new GbcRam();

  private final UndocumentedGbcRegisters undocumentedGbcRegisters = new UndocumentedGbcRegisters();

  private AddressSpace[] addressToSpace;

  public Mmu(boolean gbc) {
    addAddressSpace(ramC000);
    if (gbc) {
      addAddressSpace(gbcRam);
      addAddressSpace(undocumentedGbcRegisters);
    } else {
      addAddressSpace(ramD000);
    }
    addAddressSpace(ramFF80);
    addAddressSpace(new ShadowAddressSpace(this, 0xe000, 0xc000, 0x1e00));
  }

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
        LOG.debug(
            "Writing value {} to void address {}",
            Integer.toHexString(value),
            Integer.toHexString(address));
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

  @Override
  public Memento<Mmu> saveToMemento() {
    return new MmuMemento(ramC000.saveToMemento(), ramD000.saveToMemento(), ramFF80.saveToMemento(), gbcRam.saveToMemento(), undocumentedGbcRegisters.saveToMemento());
  }

  @Override
  public void restoreFromMemento(Memento<Mmu> memento) {
    if (!(memento instanceof MmuMemento mem)) {
      throw new IllegalArgumentException("Invalid memento type");
    }
    this.ramC000.restoreFromMemento(mem.ramC000Memento);
    this.ramD000.restoreFromMemento(mem.ramD000Memento);
    this.ramFF80.restoreFromMemento(mem.ramFF80Memento);
    this.gbcRam.restoreFromMemento(mem.gbcRamMemento);
    this.undocumentedGbcRegisters.restoreFromMemento(mem.undocumentedGbcRegistersMemento);
  }

  private record MmuMemento(  Memento<Ram> ramC000Memento, Memento<Ram> ramD000Memento, Memento<Ram> ramFF80Memento, Memento<GbcRam> gbcRamMemento, Memento<UndocumentedGbcRegisters> undocumentedGbcRegistersMemento
  ) implements Memento<Mmu> {}
}
