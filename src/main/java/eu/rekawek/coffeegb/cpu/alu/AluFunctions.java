package eu.rekawek.coffeegb.cpu.alu;

import eu.rekawek.coffeegb.cpu.BitUtils;
import eu.rekawek.coffeegb.cpu.op.DataType;

public class AluFunctions {

    private static final AluFunctionWrapper ALU = new AluFunctionWrapper();

    public static AluFunctionWrapper.IntRegistryFunction findAluFunction(String name, DataType argumentType) {
        return ALU.findAluFunction(name, argumentType);
    }

    public static AluFunctionWrapper.BiIntRegistryFunction findAluFunction(String name, DataType arg1Type, DataType arg2Type) {
        return ALU.findAluFunction(name, arg1Type, arg2Type);
    }

    static {
        ALU.registerAluFunction("INC", DataType.D8, (flags, arg) -> {
            int result = (arg + 1) & 0xff;
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH((arg & 0x0f) == 0x0f);
            return result;
        });
        ALU.registerAluFunction("INC", DataType.D16, (flags, arg) -> (arg + 1) & 0xffff);
        ALU.registerAluFunction("DEC", DataType.D8, (flags, arg) -> {
            int result = (arg - 1) & 0xff;
            flags.setZ(result == 0);
            flags.setN(true);
            flags.setH((arg & 0x0f) == 0x0);
            return result;
        });
        ALU.registerAluFunction("DEC", DataType.D16, (flags, arg) -> (arg - 1) & 0xffff);
        ALU.registerAluFunction("ADD", DataType.D16, DataType.D16, (flags, arg1, arg2) -> {
           flags.setN(false);
           flags.setH((arg1 & 0x0fff) + (arg2 & 0x0fff) > 0x0fff);
           flags.setC(arg1 + arg2 > 0xffff);
           return (arg1 + arg2) & 0xffff;
        });
        ALU.registerAluFunction("ADD", DataType.D16, DataType.R8, (flags, arg1, arg2) -> {
            flags.setZ(false);
            flags.setN(false);

            int b = BitUtils.abs(arg2);
            int word = arg1;

            if (BitUtils.isNegative(arg2)) {
                flags.setH((word & 0x0f) < (b & 0x0f));
                flags.setC((word & 0xff) < b);
                return (word - b) & 0xffff;
            } else {
                flags.setC((word & 0xff) + b > 0xff);
                flags.setH((word & 0x0f) + (b & 0x0f) > 0x0f);
                return (word + b) & 0xffff;
            }
        });
        ALU.registerAluFunction("DAA", DataType.D8, (flags, arg) -> {
            int result = arg;
            if (flags.isN()) {
                if (flags.isH()) {
                    result = (result - 6) & 0xff;
                }
                if (flags.isC()) {
                    result = (result - 0x60) & 0xff;
                }
            } else {
                if (flags.isH() || (result & 0xf) > 9) {
                    result += 0x06;
                }
                if (flags.isC() || result > 0x9f) {
                    result += 0x60;
                }
            }
            flags.setH(false);
            if (result > 0xff) {
                flags.setC(true);
            }
            result &= 0xff;
            flags.setZ(result == 0);
            return result;
        });
        ALU.registerAluFunction("CPL", DataType.D8, (flags, arg) -> {
            flags.setN(false);
            flags.setH(false);
            return (~arg) & 0xff;
        });
        ALU.registerAluFunction("SCF", DataType.D8, (flags, arg) -> {
            flags.setN(false);
            flags.setH(false);
            flags.setC(true);
            return arg;
        });
        ALU.registerAluFunction("CCF", DataType.D8, (flags, arg) -> {
            flags.setN(false);
            flags.setH(false);
            flags.setC(!flags.isC());
            return arg;
        });
        ALU.registerAluFunction("ADD", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            flags.setZ(((byte1 + byte2) & 0xff) == 0);
            flags.setN(false);
            flags.setH((byte1 & 0x0f) + (byte2 & 0x0f) > 0x0f);
            flags.setC(byte1 + byte2 > 0xff);
            return (byte1 + byte2) & 0xff;
        });
        ALU.registerAluFunction("ADC", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int carry = flags.isC() ? 1 : 0;
            flags.setZ(((byte1 + byte2 + carry) & 0xff) == 0);
            flags.setN(false);
            flags.setH((byte1 & 0x0f) + (byte2 & 0x0f) + carry > 0x0f);
            flags.setC(byte1 + byte2 + carry > 0xff);
            return (byte1 + byte2 + carry) & 0xff;
        });
        ALU.registerAluFunction("SUB", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            flags.setZ(((byte1 - byte2) & 0xff) == 0);
            flags.setN(true);
            flags.setH((0x0f & byte2) > (0x0f & byte1));
            flags.setC(byte2 > byte1);
            return (byte1 - byte2) & 0xff;
        });
        ALU.registerAluFunction("SBC", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int carry = flags.isC() ? 1 : 0;
            flags.setZ(((byte1 - byte2 - carry) & 0xff) == 0);
            flags.setN(true);
            flags.setH((0x0f & (byte2 + carry)) > (0x0f & byte1));
            flags.setC(byte2 + carry > byte1);
            return (byte1 - byte2 - carry) & 0xff;
        });
        ALU.registerAluFunction("AND", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int result = byte1 & byte2;
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(true);
            flags.setC(false);
            return result;
        });
        ALU.registerAluFunction("OR", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int result = byte1 | byte2;
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            flags.setC(false);
            return result;
        });
        ALU.registerAluFunction("XOR", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int result = (byte1 ^ byte2) & 0xff;
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            flags.setC(false);
            return result;
        });
        ALU.registerAluFunction("CP", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            flags.setZ(((byte1 - byte2) & 0xff) == 0);
            flags.setN(true);
            flags.setH((0x0f & byte2) > (0x0f & byte1));
            flags.setC(byte2 > byte1);
            return byte1;
        });
        ALU.registerAluFunction("RLC", DataType.D8, (flags, arg) -> {
            int result = (arg << 1) & 0xff;
            if ((arg & (1<<7)) != 0) {
                result |= 1;
                flags.setC(true);
            } else {
                flags.setC(false);
            }
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        ALU.registerAluFunction("RRC", DataType.D8, (flags, arg) -> {
            int result = arg >> 1;
            if ((arg & 1) == 1) {
                result |= (1 << 7);
                flags.setC(true);
            } else {
                flags.setC(false);
            }
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        ALU.registerAluFunction("RL", DataType.D8, (flags, arg) -> {
            int result = (arg << 1) & 0xff;
            result |= flags.isC() ? 1 : 0;
            flags.setC((arg & (1<<7)) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        ALU.registerAluFunction("RR", DataType.D8, (flags, arg) -> {
            int result = arg >> 1;
            result |= flags.isC() ? (1 << 7) : 0;
            flags.setC((arg & 1) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        ALU.registerAluFunction("SLA", DataType.D8, (flags, arg) -> {
            int result = (arg << 1) & 0xff;
            flags.setC((arg & (1<<7)) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        ALU.registerAluFunction("SRA", DataType.D8, (flags, arg) -> {
            int result = (arg >> 1) | (arg & (1 << 7));
            flags.setC((arg & 1) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        ALU.registerAluFunction("SWAP", DataType.D8, (flags, arg) -> {
            int upper = arg & 0xf0;
            int lower = arg & 0x0f;
            int result = (lower << 4) | (upper >> 4);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            flags.setC(false);
            return result;
        });
        ALU.registerAluFunction("SRL", DataType.D8, (flags, arg) -> {
            int result = (arg >> 1);
            flags.setC((arg & 1) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        ALU.registerAluFunction("BIT", DataType.D8, DataType.D8, (flags, arg1, arg2) -> {
            int bit = arg2;
            flags.setN(false);
            flags.setH(true);
            if (bit < 8) {
                flags.setZ(BitUtils.getBit(arg1, arg2));
            }
            return arg1;
        });
        ALU.registerAluFunction("RES", DataType.D8, DataType.D8, (flags, arg1, arg2) -> BitUtils.clearBit(arg1, arg2));
        ALU.registerAluFunction("SET", DataType.D8, DataType.D8, (flags, arg1, arg2) -> BitUtils.setBit(arg1, arg2));
    }

}
