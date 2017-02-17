package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;

public class UndocumentedGbcRegisters implements AddressSpace {

    private Ram ram = new Ram(0xff72, 6);

    private int xff6c;

    public UndocumentedGbcRegisters() {
        xff6c = 0xfe;
        ram.setByte(0xff74, 0xff);
        ram.setByte(0xff75, 0x8f);
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff6c || ram.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case 0xff6c:
                xff6c = 0xfe | (value & 1);
                break;

            case 0xff72:
            case 0xff73:
            case 0xff74:
                ram.setByte(address, value);
                break;

            case 0xff75:
                ram.setByte(address, 0x8f | (value & 0b01110000));
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff6c) {
            return xff6c;
        } else if (ram.accepts(address)) {
            return ram.getByte(address);
        } else {
            throw new IllegalArgumentException();
        }
    }
}
