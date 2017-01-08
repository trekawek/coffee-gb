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

    public static int toWord(int[] bytes) {
        return toWord(bytes[1], bytes[0]);
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
        return (byteValue | (1 << position)) & 0xff;
    }

    public static int clearBit(int byteValue, int position) {
        checkByteArgument("byteValue", byteValue);
        return ~(1 << position) & byteValue & 0xff;
    }

    public static boolean isNegative(int signedByteValue) {
        checkByteArgument("byteValue", signedByteValue);
        return (signedByteValue & (1 << 7)) != 0;
    }

    public static int abs(int signedByteValue) {
        checkByteArgument("signedByteValue", signedByteValue);
        if (isNegative(signedByteValue)) {
            return 0x100 - signedByteValue;
        } else {
            return signedByteValue;
        }
    }

    public static int addSignedByte(int word, int signedByteValue) {
        if (isNegative(signedByteValue)) {
            return word - abs(signedByteValue);
        } else {
            return word + abs(signedByteValue);
        }
    }

    static void checkByteArgument(String argumentName, int argument) {
        checkArgument(argument >= 0 && argument <= 0xff, "Argument {} should be a byte", argumentName);
    }

    static void checkWordArgument(String argumentName, int argument) {
        checkArgument(argument >= 0 && argument <= 0xffff, "Argument {} should be a word", argumentName);
    }

}
