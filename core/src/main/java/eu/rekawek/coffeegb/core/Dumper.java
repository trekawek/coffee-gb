package eu.rekawek.coffeegb.core;

import static eu.rekawek.coffeegb.core.gpu.Fetcher.zip;

public final class Dumper {

    private Dumper() {
    }

    public static void dump(int[] data, int offset, int length) {
        for (int i = offset; i < (offset + length); i++) {
            System.out.printf("%02X ", data[i]);
            if ((i - offset + 1) % 16 == 0) {
                System.out.print(" ");
                //dumpText(data, i - 16);
                System.out.println();
            }
        }
    }

    private static void dumpText(int[] data, int offset) {
        for (int i = 0; i < 16; i++) {
            System.out.print((char) data[offset + i]);
        }
    }

    public static void dump(AddressSpace addressSpace, int offset, int length) {
        for (int i = offset; i < (offset + length); i++) {
            System.out.printf("%02X ", addressSpace.getByte(i));
            if ((i - offset + 1) % 16 == 0) {
                System.out.print(" ");
                dumpText(addressSpace, i - 16);
                System.out.println();
            }
        }
    }

    private static void dumpText(AddressSpace addressSpace, int offset) {
        for (int i = 0; i < 16; i++) {
            System.out.print((char) addressSpace.getByte(offset + i));
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
