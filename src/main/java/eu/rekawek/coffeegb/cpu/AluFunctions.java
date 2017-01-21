package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.cpu.op.DataType;

import java.util.HashMap;
import java.util.Map;

public class AluFunctions {

    private Map<FunctionKey, IntRegistryFunction> functions = new HashMap<>();

    private Map<FunctionKey, BiIntRegistryFunction> biFunctions = new HashMap<>();

    public IntRegistryFunction findAluFunction(String name, DataType argumentType) {
        return functions.get(new FunctionKey(name, argumentType));
    }

    public BiIntRegistryFunction findAluFunction(String name, DataType arg1Type, DataType arg2Type) {
        return biFunctions.get(new FunctionKey(name, arg1Type, arg2Type));
    }

    private void registerAluFunction(String name, DataType dataType, IntRegistryFunction function) {
        functions.put(new FunctionKey(name, dataType), function);
    }

    private void registerAluFunction(String name, DataType dataType1, DataType dataType2, BiIntRegistryFunction function) {
        biFunctions.put(new FunctionKey(name, dataType1, dataType2), function);
    }

    {
        registerAluFunction("INC", DataType.D8, (flags, arg) -> {
            int result = (arg + 1) & 0xff;
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH((arg & 0x0f) == 0x0f);
            return result;
        });
        registerAluFunction("INC", DataType.D16, (flags, arg) -> (arg + 1) & 0xffff);
        registerAluFunction("DEC", DataType.D8, (flags, arg) -> {
            int result = (arg - 1) & 0xff;
            flags.setZ(result == 0);
            flags.setN(true);
            flags.setH((arg & 0x0f) == 0x0);
            return result;
        });
        registerAluFunction("DEC", DataType.D16, (flags, arg) -> (arg - 1) & 0xffff);
        registerAluFunction("ADD", DataType.D16, DataType.D16, (flags, arg1, arg2) -> {
           flags.setN(false);
           flags.setH((arg1 & 0x0fff) + (arg2 & 0x0fff) > 0x0fff);
           flags.setC(arg1 + arg2 > 0xffff);
           return (arg1 + arg2) & 0xffff;
        });
        registerAluFunction("ADD", DataType.D16, DataType.R8, (flags, arg1, arg2) -> (arg1 + arg2) & 0xffff);
        registerAluFunction("ADD_SP", DataType.D16, DataType.R8, (flags, arg1, arg2) -> {
            flags.setZ(false);
            flags.setN(false);

            int result = arg1 + arg2;
            flags.setC((((arg1 & 0xff) + (arg2 & 0xff)) & 0x100) != 0);
            flags.setH((((arg1 & 0x0f) + (arg2 & 0x0f)) & 0x10) != 0);
            return result & 0xffff;
        });
        registerAluFunction("DAA", DataType.D8, (flags, arg) -> {
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
        registerAluFunction("CPL", DataType.D8, (flags, arg) -> {
            flags.setN(true);
            flags.setH(true);
            return (~arg) & 0xff;
        });
        registerAluFunction("SCF", DataType.D8, (flags, arg) -> {
            flags.setN(false);
            flags.setH(false);
            flags.setC(true);
            return arg;
        });
        registerAluFunction("CCF", DataType.D8, (flags, arg) -> {
            flags.setN(false);
            flags.setH(false);
            flags.setC(!flags.isC());
            return arg;
        });
        registerAluFunction("ADD", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            flags.setZ(((byte1 + byte2) & 0xff) == 0);
            flags.setN(false);
            flags.setH((byte1 & 0x0f) + (byte2 & 0x0f) > 0x0f);
            flags.setC(byte1 + byte2 > 0xff);
            return (byte1 + byte2) & 0xff;
        });
        registerAluFunction("ADC", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int carry = flags.isC() ? 1 : 0;
            flags.setZ(((byte1 + byte2 + carry) & 0xff) == 0);
            flags.setN(false);
            flags.setH((byte1 & 0x0f) + (byte2 & 0x0f) + carry > 0x0f);
            flags.setC(byte1 + byte2 + carry > 0xff);
            return (byte1 + byte2 + carry) & 0xff;
        });
        registerAluFunction("SUB", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            flags.setZ(((byte1 - byte2) & 0xff) == 0);
            flags.setN(true);
            flags.setH((0x0f & byte2) > (0x0f & byte1));
            flags.setC(byte2 > byte1);
            return (byte1 - byte2) & 0xff;
        });
        registerAluFunction("SBC", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int carry = flags.isC() ? 1 : 0;
            int res = byte1 - byte2 - carry;

            flags.setZ((res & 0xff) == 0);
            flags.setN(true);
            flags.setH(((byte1 ^ byte2 ^ (res & 0xff)) & (1 << 4)) != 0);
            flags.setC(res < 0);
            return res & 0xff;
        });
        registerAluFunction("AND", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int result = byte1 & byte2;
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(true);
            flags.setC(false);
            return result;
        });
        registerAluFunction("OR", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int result = byte1 | byte2;
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            flags.setC(false);
            return result;
        });
        registerAluFunction("XOR", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            int result = (byte1 ^ byte2) & 0xff;
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            flags.setC(false);
            return result;
        });
        registerAluFunction("CP", DataType.D8, DataType.D8, (flags, byte1, byte2) -> {
            flags.setZ(((byte1 - byte2) & 0xff) == 0);
            flags.setN(true);
            flags.setH((0x0f & byte2) > (0x0f & byte1));
            flags.setC(byte2 > byte1);
            return byte1;
        });
        registerAluFunction("RLC", DataType.D8, (flags, arg) -> {
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
        registerAluFunction("RRC", DataType.D8, (flags, arg) -> {
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
        registerAluFunction("RL", DataType.D8, (flags, arg) -> {
            int result = (arg << 1) & 0xff;
            result |= flags.isC() ? 1 : 0;
            flags.setC((arg & (1<<7)) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        registerAluFunction("RR", DataType.D8, (flags, arg) -> {
            int result = arg >> 1;
            result |= flags.isC() ? (1 << 7) : 0;
            flags.setC((arg & 1) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        registerAluFunction("SLA", DataType.D8, (flags, arg) -> {
            int result = (arg << 1) & 0xff;
            flags.setC((arg & (1<<7)) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        registerAluFunction("SRA", DataType.D8, (flags, arg) -> {
            int result = (arg >> 1) | (arg & (1 << 7));
            flags.setC((arg & 1) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        registerAluFunction("SWAP", DataType.D8, (flags, arg) -> {
            int upper = arg & 0xf0;
            int lower = arg & 0x0f;
            int result = (lower << 4) | (upper >> 4);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            flags.setC(false);
            return result;
        });
        registerAluFunction("SRL", DataType.D8, (flags, arg) -> {
            int result = (arg >> 1);
            flags.setC((arg & 1) != 0);
            flags.setZ(result == 0);
            flags.setN(false);
            flags.setH(false);
            return result;
        });
        registerAluFunction("BIT", DataType.D8, DataType.D8, (flags, arg1, arg2) -> {
            int bit = arg2;
            flags.setN(false);
            flags.setH(true);
            if (bit < 8) {
                flags.setZ(!BitUtils.getBit(arg1, arg2));
            }
            return arg1;
        });
        registerAluFunction("RES", DataType.D8, DataType.D8, (flags, arg1, arg2) -> BitUtils.clearBit(arg1, arg2));
        registerAluFunction("SET", DataType.D8, DataType.D8, (flags, arg1, arg2) -> BitUtils.setBit(arg1, arg2));
    }

    public interface IntRegistryFunction {
        int apply(Flags flags, int arg);
    }

    public interface BiIntRegistryFunction {
        int apply(Flags flags, int arg1, int arg2);
    }

    private static class FunctionKey {

        private final String name;

        private final DataType type1;

        private final DataType type2;

        public FunctionKey(String name, DataType type1, DataType type2) {
            this.name = name;
            this.type1 = type1;
            this.type2 = type2;
        }

        public FunctionKey(String name, DataType type) {
            this.name = name;
            this.type1 = type;
            this.type2 = null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            FunctionKey that = (FunctionKey) o;

            if (!name.equals(that.name)) return false;
            if (!type1.equals(that.type1)) return false;
            return type2 != null ? type2.equals(that.type2) : that.type2 == null;
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + type1.hashCode();
            result = 31 * result + (type2 != null ? type2.hashCode() : 0);
            return result;
        }
    }
}
