package eu.rekawek.coffeegb.cpu;

import static com.google.common.base.Preconditions.checkArgument;

public final class BitUtils {

    private BitUtils() {
    }

    public static int getMSB(int word) {
        checkWordArgument("word", word);
        return word >> 8;
    }

    public static int getLSB(int word) {
        checkWordArgument("word", word);
        return word & 0xff;
    }

    public static int toWord(int msb, int lsb) {
        checkByteArgument("msb", msb);
        checkByteArgument("lsb", lsb);
        return (msb << 8) | lsb;
    }

    public static boolean getBit(int byteValue, int position) {
        return (byteValue & (1 << position)) != 0;
    }

    public static int setBit(int byteValue, int position, boolean value) {
        return value ? setBit(byteValue, position) : clearBit(byteValue, position);
    }

    public static int setBit(int byteValue, int position) {
        checkByteArgument("byteValue", byteValue);
        return byteValue | (1 << position);
    }

    public static int clearBit(int byteValue, int position) {
        checkByteArgument("byteValue", byteValue);
        return ~(1 << position) & byteValue & 0xff;
    }

    static void checkByteArgument(String argumentName, int argument) {
        checkArgument(argument >= 0 && argument <= 0xff, "Argument {} should be a byte", argumentName);
    }

    static void checkWordArgument(String argumentName, int argument) {
        checkArgument(argument >= 0 && argument <= 0xffff, "Argument {} should be a word", argumentName);
    }
}
