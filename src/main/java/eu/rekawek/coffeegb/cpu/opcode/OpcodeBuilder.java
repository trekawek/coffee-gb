package eu.rekawek.coffeegb.cpu.opcode;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.Registers;
import eu.rekawek.coffeegb.cpu.alu.AluFunctionWrapper;
import eu.rekawek.coffeegb.cpu.alu.AluFunctions;
import eu.rekawek.coffeegb.cpu.op.Argument;
import eu.rekawek.coffeegb.cpu.op.DataType;
import eu.rekawek.coffeegb.cpu.op.Op;

import java.util.ArrayList;
import java.util.List;

public class OpcodeBuilder {

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
            public int operangeLength() {
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
        if (lastDataType != arg.getDataType() && !(lastDataType == DataType.D16 && arg == Argument._a16)) {
            throw new IllegalStateException("Can't write " + lastDataType + " to " + target);
        }
        ops.add(new Op() {
            @Override
            public boolean writesMemory() {
                return arg.isMemory();
            }

            @Override
            public int operangeLength() {
                return arg.getOperandLength();
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                arg.write(registers, addressSpace, args, context);
                return context;
            }
        });
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
        ops.add(new Op() {
            @Override
            public boolean writesMemory() {
                return true;
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                registers.decrementSP();
                addressSpace.setByte(registers.getSP(), (context & 0xff00) >> 8);
                return context;
            }
        });
        ops.add(new Op() {
            @Override
            public boolean writesMemory() {
                return true;
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                registers.decrementSP();
                addressSpace.setByte(registers.getSP(), context & 0x00ff);
                return context;
            }
        });
        return this;
    }

    public OpcodeBuilder pop() {
        lastDataType = DataType.D16;
        ops.add(new Op() {
            @Override
            public boolean readsMemory() {
                return true;
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
                int lsb = addressSpace.getByte(registers.getSP());
                registers.incrementSP();
                return lsb;
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
                registers.incrementSP();
                return context | (msb << 8);
            }
        });
        return this;
    }

    public OpcodeBuilder alu(String operation, String argument2) {
        Argument arg2 = Argument.parse(argument2);
        AluFunctionWrapper.BiIntRegistryFunction func = AluFunctions.findAluFunction(operation, lastDataType, arg2.getDataType());
        ops.add(new Op() {
            @Override
            public boolean readsMemory() {
                return arg2.isMemory();
            }

            @Override
            public int operangeLength() {
                return arg2.getOperandLength();
            }

            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int v1) {
                int v2 = arg2.read(registers, addressSpace, args);
                return func.apply(registers.getFlags(), v1, v2);
            }
        });
        return this;
    }

    public OpcodeBuilder alu(String operation, int d8Value) {
        AluFunctionWrapper.BiIntRegistryFunction func = AluFunctions.findAluFunction(operation, lastDataType, DataType.D8);
        ops.add(new Op() {
            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int v1) {
                return func.apply(registers.getFlags(), v1, d8Value);
            }
        });
        return this;
    }

    public OpcodeBuilder alu(String operation) {
        AluFunctionWrapper.IntRegistryFunction func = AluFunctions.findAluFunction(operation, lastDataType);
        ops.add(new Op() {
            @Override
            public int execute(Registers registers, AddressSpace addressSpace, int[] args, int value) {
                return func.apply(registers.getFlags(), value);
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
}
