package eu.rekawek.coffeegb;

import static eu.rekawek.coffeegb.gpu.Fetcher.zip;

public final class Dumper {

    private Dumper() {
    }

    public static void dump(AddressSpace addressSpace, int offset, int length) {
        for (int i = offset; i < (offset + length); i++) {
            System.out.print(String.format("%02X ", addressSpace.getByte(i)));
            if ((i - offset + 1) % 16 == 0) {
                //System.out.print(" ");
                //dumpText(addressSpace, i - 16);
                System.out.println();
            }
        }
    }

    private static void dumpText(AddressSpace addressSpace, int offset) {
        for (int i = 0; i < 16; i++) {
            System.out.print(Character.toString((char) addressSpace.getByte(offset + i)));
        }
    }

    public static void dumpTile(AddressSpace addressSpace, int offset) {
        for (int i = 0; i < 16; i += 2) {
            dumpTileLine(addressSpace.getByte(offset + i), addressSpace.getByte(offset + i + 1));
            System.out.println();
        }
    }

    public static void dumpTileLine(int data1, int data2) {
        for (int pixel : zip(data1, data2, false, new int[8])) {
            if (pixel == 0) {
                System.out.print('.');
            } else {
                System.out.print(pixel);
            }
        }
    }
}
