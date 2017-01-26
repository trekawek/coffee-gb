package eu.rekawek.coffeegb.cpu.opcode;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.BitUtils;
import eu.rekawek.coffeegb.cpu.Flags;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.Registers;
import eu.rekawek.coffeegb.cpu.AluFunctions;
import eu.rekawek.coffeegb.cpu.op.Argument;
import eu.rekawek.coffeegb.cpu.op.DataType;
import eu.rekawek.coffeegb.cpu.op.Op;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static eu.rekawek.coffeegb.cpu.BitUtils.toWord;

public class OpcodeBuilder {

    private static final AluFunctions ALU = new AluFunctions();

    private static final Set<AluFunctions.IntRegistryFunction> OEM_BUG;
    static {
        Set<AluFunctions.IntRegistryFunction> oemBugFunctions = new HashSet<>();
        oemBugFunctions.add(ALU.findAluFunction("INC", DataType.D16));
        oemBugFunctions.add(ALU.findAluFunction("DEC", DataType.D16));
        OEM_BUG = Collections.unmodifiableSet(oemBugFunctions);
    }

    private final int opcode;

    private final String label;

    private final List<Op> ops = new ArrayList<>();

    private DataType lastDataType;

    public OpcodeBuilder(int opcode, String label) {
        this.opcode = opcode;
        this.label = label;
    }

    public OpcodeBuilder copyByte(String target, String source) {
        load(source);
        store(target);
        return this;
    }

    public OpcodeBuilder load(String source) {
        Argument arg = Argument.parse(source);
        lastDataType = arg.getDataType();
        ops.add(new Op() {
            @Override
            public boolean readsMemory() {
                return arg.isMemory();
            }

            @Override
            public int operandLength() {
                return arg.getOperandLength();
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                return arg.read(registers, addressSpace, args);
            }
        });
        return this;
    }

    public OpcodeBuilder loadWord(int value) {
        lastDataType = DataType.D16;
        ops.add(new Op() {
            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                return value;
            }
        });
        return this;
    }

    public OpcodeBuilder store(String target) {
        Argument arg = Argument.parse(target);
        if (lastDataType == DataType.D16 && arg == Argument._a16) {
            ops.add(new Op() {
                @Override
                public boolean writesMemory() {
                    return arg.isMemory();
                }

                @Override
                public int operandLength() {
                    return arg.getOperandLength();
                }

                @Override
                public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                    addressSpace.setByte(toWord(args), context & 0x00ff);
                    return context;
                }
            });
            ops.add(new Op() {
                @Override
                public boolean writesMemory() {
                    return arg.isMemory();
                }

                @Override
                public int operandLength() {
                    return arg.getOperandLength();
                }

                @Override
                public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                    addressSpace.setByte((toWord(args) + 1) & 0xffff, (context & 0xff00) >> 8);
                    return context;
                }
            });
        } else if (lastDataType == arg.getDataType()) {
            ops.add(new Op() {
                @Override
                public boolean writesMemory() {
                    return arg.isMemory();
                }

                @Override
                public int operandLength() {
                    return arg.getOperandLength();
                }

                @Override
                public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                    arg.write(registers, addressSpace, args, context);
                    return context;
                }
            });
        } else {
            throw new IllegalStateException("Can't write " + lastDataType + " to " + target);
        }
        return this;
    }

    public OpcodeBuilder proceedIf(String condition) {
        ops.add(new Op() {
            @Override
            public boolean proceed(Registers registers) {
                switch (condition) {
                    case "NZ":
                        return !registers.getFlags().isZ();

                    case "Z":
                        return registers.getFlags().isZ();

                    case "NC":
                        return !registers.getFlags().isC();

                    case "C":
                        return registers.getFlags().isC();
                }
                return false;
            }
        });
        return this;
    }

    public OpcodeBuilder push() {
        AluFunctions.IntRegistryFunction dec = ALU.findAluFunction("DEC", DataType.D16);
        ops.add(new Op() {
            @Override
            public boolean writesMemory() {
                return true;
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                registers.setSP(dec.apply(registers.getFlags(), registers.getSP()));
                addressSpace.setByte(registers.getSP(), (context & 0xff00) >> 8);
                return context;
            }

            @Override
            public boolean causesOemBug(Registers registers, int context) {
                return OpcodeBuilder.this.causesOemBug(dec, registers.getSP());
            }
        });
        ops.add(new Op() {
            @Override
            public boolean writesMemory() {
                return true;
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                registers.setSP(dec.apply(registers.getFlags(), registers.getSP()));
                addressSpace.setByte(registers.getSP(), context & 0x00ff);
                return context;
            }

            @Override
            public boolean causesOemBug(Registers registers, int context) {
                return OpcodeBuilder.this.causesOemBug(dec, registers.getSP());
            }
        });
        return this;
    }

    public OpcodeBuilder pop() {
        AluFunctions.IntRegistryFunction inc = ALU.findAluFunction("INC", DataType.D16);

        lastDataType = DataType.D16;
        ops.add(new Op() {
            @Override
            public boolean readsMemory() {
                return true;
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                int lsb = addressSpace.getByte(registers.getSP());
                registers.setSP(inc.apply(registers.getFlags(), registers.getSP()));
                return lsb;
            }

            @Override
            public boolean causesOemBug(Registers registers, int context) {
                return OpcodeBuilder.this.causesOemBug(inc, registers.getSP());
            }
        });
        ops.add(new Op() {
            @Override
            public boolean readsMemory() {
                return true;
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                int msb = addressSpace.getByte(registers.getSP());
                registers.setSP(inc.apply(registers.getFlags(), registers.getSP()));
                return context | (msb << 8);
            }

            @Override
            public boolean causesOemBug(Registers registers, int context) {
                return OpcodeBuilder.this.causesOemBug(inc, registers.getSP());
            }
        });
        return this;
    }

    public OpcodeBuilder alu(String operation, String argument2) {
        Argument arg2 = Argument.parse(argument2);
        AluFunctions.BiIntRegistryFunction func = ALU.findAluFunction(operation, lastDataType, arg2.getDataType());
        ops.add(new Op() {
            @Override
            public boolean readsMemory() {
                return arg2.isMemory();
            }

            @Override
            public int operandLength() {
                return arg2.getOperandLength();
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int v1) {
                int v2 = arg2.read(registers, addressSpace, args);
                return func.apply(registers.getFlags(), v1, v2);
            }
        });
        if (lastDataType == DataType.D16) {
            extraCycle();
        }
        return this;
    }

    public OpcodeBuilder alu(String operation, int d8Value) {
        AluFunctions.BiIntRegistryFunction func = ALU.findAluFunction(operation, lastDataType, DataType.D8);
        ops.add(new Op() {
            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int v1) {
                return func.apply(registers.getFlags(), v1, d8Value);
            }
        });
        if (lastDataType == DataType.D16) {
            extraCycle();
        }
        return this;
    }

    public OpcodeBuilder alu(String operation) {
        AluFunctions.IntRegistryFunction func = ALU.findAluFunction(operation, lastDataType);
        ops.add(new Op() {
            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int value) {
                return func.apply(registers.getFlags(), value);
            }

            @Override
            public boolean causesOemBug(Registers registers, int context) {
                return OpcodeBuilder.this.causesOemBug(func, context);
            }
        });
        if (lastDataType == DataType.D16) {
            extraCycle();
        }
        return this;
    }

    public OpcodeBuilder aluHL(String operation) {
        load("HL");
        AluFunctions.IntRegistryFunction func = ALU.findAluFunction(operation, DataType.D16);
        ops.add(new Op() {
            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int value) {
                return func.apply(registers.getFlags(), value);
            }

            @Override
            public boolean causesOemBug(Registers registers, int context) {
                return OpcodeBuilder.this.causesOemBug(func, context);
            }
        });
        store("HL");
        return this;
    }

    public OpcodeBuilder bitHL(int bit) {
        ops.add(new Op() {
            @Override
            public boolean readsMemory() {
                return true;
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                int value = addressSpace.getByte(registers.getHL());
                Flags flags = registers.getFlags();
                flags.setN(false);
                flags.setH(true);
                if (bit < 8) {
                    flags.setZ(!BitUtils.getBit(value, bit));
                }
                return context;
            }
        });
        return this;
    }

    public OpcodeBuilder clearZ() {
        ops.add(new Op() {
            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                registers.getFlags().setZ(false);
                return context;
            }
        });
        return this;
    }

    public OpcodeBuilder switchInterrupts(boolean enable, boolean withDelay) {
        ops.add(new Op() {
            @Override
            public void switchInterrupts(InterruptManager interruptManager) {
                if (enable) {
                    interruptManager.enableInterrupts(withDelay);
                } else {
                    interruptManager.disableInterrupts(withDelay);
                }
            }
        });
        return this;
    }

    public OpcodeBuilder op(Op op) {
        ops.add(op);
        return this;
    }

    public OpcodeBuilder extraCycle() {
        ops.add(new Op() {
            @Override
            public boolean readsMemory() {
                return true;
            }
        });
        return this;
    }

    public OpcodeBuilder forceFinish() {
        ops.add(new Op() {
            @Override
            public boolean forceFinishCycle() {
                return true;
            }
        });
        return this;
    }

    public Opcode build() {
        return new Opcode(this);
    }

    int getOpcode() {
        return opcode;
    }

    String getLabel() {
        return label;
    }

    List<Op> getOps() {
        return ops;
    }

    @Override
    public String toString() {
        return label;
    }

    private boolean causesOemBug(AluFunctions.IntRegistryFunction function, int context) {
        return OEM_BUG.contains(function) && context >= 0xfe00 && context <= 0xfeff;
    }

}
