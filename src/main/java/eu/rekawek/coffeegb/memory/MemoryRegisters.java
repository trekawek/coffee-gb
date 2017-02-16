package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MemoryRegisters implements AddressSpace {

    public interface Register {
        int getAddress();

        RegisterType getType();
    }

    public enum RegisterType {
        R(true, false), W(false, true), RW(true, true);

        private final boolean allowsRead;

        private final boolean allowsWrite;

        RegisterType(boolean allowsRead, boolean allowsWrite) {
            this.allowsRead = allowsRead;
            this.allowsWrite = allowsWrite;
        }
    }

    private Map<Integer, Register> registers;

    private Map<Integer, Integer> values = new HashMap<>();

    public MemoryRegisters(Register... registers) {
        Map<Integer, Register> map = new HashMap<>();
        for (Register r : registers) {
            if (map.containsKey(r.getAddress())) {
                throw new IllegalArgumentException("Two registers with the same address: " + r.getAddress());
            }
            map.put(r.getAddress(), r);
            values.put(r.getAddress(), 0);
        }
        this.registers = Collections.unmodifiableMap(map);
    }

    private MemoryRegisters(MemoryRegisters original) {
        this.registers = original.registers;
        this.values = Collections.unmodifiableMap(new HashMap<>(original.values));
    }

    public int get(Register reg) {
        if (registers.containsKey(reg.getAddress())) {
            return values.get(reg.getAddress());
        } else {
            throw new IllegalArgumentException("Not valid register: " + reg);
        }
    }

    public void put(Register reg, int value) {
        if (registers.containsKey(reg.getAddress())) {
            values.put(reg.getAddress(), value);
        } else {
            throw new IllegalArgumentException("Not valid register: " + reg);
        }
    }

    public MemoryRegisters freeze() {
        return new MemoryRegisters(this);
    }

    public int preIncrement(Register reg) {
        if (registers.containsKey(reg.getAddress())) {
            int value = values.get(reg.getAddress()) + 1;
            values.put(reg.getAddress(), value);
            return value;
        } else {
            throw new IllegalArgumentException("Not valid register: " + reg);
        }
    }

    @Override
    public boolean accepts(int address) {
        return registers.containsKey(address);
    }

    @Override
    public void setByte(int address, int value) {
        if (registers.get(address).getType().allowsWrite) {
            values.put(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        if (registers.get(address).getType().allowsRead) {
            return values.get(address);
        } else {
            return 0xff;
        }
    }
}
