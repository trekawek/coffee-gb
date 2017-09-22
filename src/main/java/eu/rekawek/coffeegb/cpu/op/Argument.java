package eu.rekawek.coffeegb.cpu.op;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.BitUtils;
import eu.rekawek.coffeegb.cpu.Registers;

import static eu.rekawek.coffeegb.cpu.BitUtils.toSigned;

public enum Argument {

    A {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getA();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setA(value);
        }
    }, B {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getB();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setB(value);
        }
    }, C {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getC();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setC(value);
        }
    }, D {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getD();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setD(value);
        }
    }, E {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getE();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setE(value);
        }
    }, H {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getH();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setH(value);
        }
    }, L {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getL();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setL(value);
        }
    }, AF("AF", 0, false, DataType.D16) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getAF();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setAF(value);
        }
    }, BC("BC", 0, false, DataType.D16) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getBC();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setBC(value);
        }
    }, DE("DE", 0, false, DataType.D16) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getDE();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setDE(value);

        }
    }, HL("HL", 0, false, DataType.D16) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getHL();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setHL(value);
        }
    }, SP("SP", 0, false, DataType.D16) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getSP();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setSP(value);
        }
    }, PC("PC", 0, false, DataType.D16) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return registers.getPC();
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            registers.setPC(value);
        }
    },
    d8("d8", 1, false, DataType.D8) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return args[0];
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            throw new UnsupportedOperationException();
        }
    }, d16("d16", 2, false, DataType.D16) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return BitUtils.toWord(args);
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            throw new UnsupportedOperationException();
        }
    }, r8("r8", 1, false, DataType.R8) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return toSigned(args[0]);
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            throw new UnsupportedOperationException();
        }
    }, a16("a16", 2, false, DataType.D16) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return BitUtils.toWord(args);
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            throw new UnsupportedOperationException();
        }
    }, _BC("(BC)", 0, true, DataType.D8) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return addressSpace.getByte(registers.getBC());
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            addressSpace.setByte(registers.getBC(), value);
        }
    }, _DE("(DE)", 0, true, DataType.D8) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return addressSpace.getByte(registers.getDE());
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            addressSpace.setByte(registers.getDE(), value);
        }
    }, _HL("(HL)", 0, true, DataType.D8) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return addressSpace.getByte(registers.getHL());
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            addressSpace.setByte(registers.getHL(), value);
        }
    },
    _a8("(a8)", 1, true, DataType.D8) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return addressSpace.getByte(0xff00 | args[0]);
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            addressSpace.setByte(0xff00 | args[0], value);
        }
    }, _a16("(a16)", 2, true, DataType.D8) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return addressSpace.getByte(BitUtils.toWord(args));
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            addressSpace.setByte(BitUtils.toWord(args), value);
        }
    }, _C("(C)", 0, true, DataType.D8) {
        @Override
        public int read(Registers registers, AddressSpace addressSpace, int[] args) {
            return addressSpace.getByte(0xff00 | registers.getC());
        }

        @Override
        public void write(Registers registers, AddressSpace addressSpace, int[] args, int value) {
            addressSpace.setByte(0xff00 | registers.getC(), value);
        }
    };

    private final String label;

    private final int operandLength;

    private final boolean memory;

    private final DataType dataType;

    Argument() {
        this(null, 0, false, DataType.D8);
    }

    Argument(String label, int operandLength, boolean memory, DataType dataType) {
        this.label = label == null ? name() : label;
        this.operandLength = operandLength;
        this.memory = memory;
        this.dataType = dataType;
    }

    public int getOperandLength() {
        return operandLength;
    }

    public boolean isMemory() {
        return memory;
    }

    public abstract int read(Registers registers, AddressSpace addressSpace, int[] args);

    public abstract void write(Registers registers, AddressSpace addressSpace, int[] args, int value);

    public DataType getDataType() {
        return dataType;
    }

    public static Argument parse(String string) {
        for (Argument a : values()) {
            if (a.label.equals(string)) {
                return a;
            }
        }
        throw new IllegalArgumentException("Unknown argument: " + string);
    }

    public String getLabel() {
        return label;
    }
}
