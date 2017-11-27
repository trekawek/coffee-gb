package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;

public class GbcRam implements AddressSpace {

    private int[] ram = new int[7 * 0x1000];

    private int svbk;

    @Override
    public boolean accepts(int address) {
        return address == 0xff70 || (address >= 0xd000 && address < 0xe000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address == 0xff70) {
            this.svbk = value;
        } else {
            ram[translate(address)] = value;
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff70) {
            return svbk;
        } else {
            return ram[translate(address)];
        }
    }

    private int translate(int address) {
        int ramBank = svbk & 0x7;
        if (ramBank == 0) {
            ramBank = 1;
        }
        int result = address - 0xd000 + (ramBank - 1) * 0x1000;
        if (result < 0 || result >= ram.length) {
            throw new IllegalArgumentException();
        }
        return result;
    }

}
