package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.cpu.opcode.OpcodeBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public final class Opcodes {

    public static final List<Opcode> COMMANDS;

    public static final List<Opcode> EXT_COMMANDS;

    static {
        OpcodeBuilder[] opcodes = new OpcodeBuilder[0x100];
        OpcodeBuilder[] extOpcodes = new OpcodeBuilder[0x100];

        regCmd(opcodes, 0x00, "NOP");

        for (Entry<Integer, String> t : indexedList(0x01, 0x10, "BC", "DE", "HL", "SP")) {
            regLoad(opcodes, t.getKey(), t.getValue(), "d16");
        }

        for (Entry<Integer, String> t : indexedList(0x02, 0x10, "(BC)", "(DE)")) {
            regLoad(opcodes, t.getKey(), t.getValue(), "A");
        }

        for (Entry<Integer, String> t : indexedList(0x03, 0x10, "BC", "DE", "HL", "SP")) {
            regCmd(opcodes, t, "INC {}").load(t.getValue()).alu("INC").store(t.getValue());
        }

        for (Entry<Integer, String> t : indexedList(0x04, 0x08, "B", "C", "D", "E", "H", "L", "(HL)", "A")) {
            regCmd(opcodes, t, "INC {}").load(t.getValue()).alu("INC").store(t.getValue());
        }

        for (Entry<Integer, String> t : indexedList(0x05, 0x08, "B", "C", "D", "E", "H", "L", "(HL)", "A")) {
            regCmd(opcodes, t, "DEC {}").load(t.getValue()).alu("DEC").store(t.getValue());
        }

        for (Entry<Integer, String> t : indexedList(0x06, 0x08, "B", "C", "D", "E", "H", "L", "(HL)", "A")) {
            regLoad(opcodes, t.getKey(), t.getValue(), "d8");
        }

        for (Entry<Integer, String> o : indexedList(0x07, 0x08, "RLC", "RRC", "RL", "RR")) {
            regCmd(opcodes, o, o.getValue() + "A").load("A").alu(o.getValue()).clearZ().store("A");
        }

        regLoad(opcodes, 0x08, "(a16)", "SP");

        for (Entry<Integer, String> t : indexedList(0x09, 0x10, "BC", "DE", "HL", "SP")) {
            regCmd(opcodes, t, "ADD HL,{}").load("HL").alu("ADD", t.getValue()).store("HL");
        }

        for (Entry<Integer, String> t : indexedList(0x0a, 0x10, "(BC)", "(DE)")) {
            regLoad(opcodes, t.getKey(), "A", t.getValue());
        }

        for (Entry<Integer, String> t : indexedList(0x0b, 0x10, "BC", "DE", "HL", "SP")) {
            regCmd(opcodes, t, "DEC {}").load(t.getValue()).alu("DEC").store(t.getValue());
        }

        regCmd(opcodes, 0x10, "STOP");

        regCmd(opcodes, 0x18, "JR r8").load("PC").alu("ADD", "r8").store("PC");

        for (Entry<Integer, String> c : indexedList(0x20, 0x08, "NZ", "Z", "NC", "C")) {
            regCmd(opcodes, c, "JR {},r8").load("PC").proceedIf(c.getValue()).alu("ADD", "r8").store("PC");
        }

        regCmd(opcodes, 0x22, "LD (HL+),A").copyByte("(HL)", "A").aluHL("INC");
        regCmd(opcodes, 0x2a, "LD A,(HL+)").copyByte("A", "(HL)").aluHL("INC");

        regCmd(opcodes, 0x27, "DAA").load("A").alu("DAA").store("A");
        regCmd(opcodes, 0x2f, "CPL").load("A").alu("CPL").store("A");

        regCmd(opcodes, 0x32, "LD (HL-),A").copyByte("(HL)", "A").aluHL("DEC");
        regCmd(opcodes, 0x3a, "LD A,(HL-)").copyByte("A", "(HL)").aluHL("DEC");

        regCmd(opcodes, 0x37, "SCF").load("A").alu("SCF").store("A");
        regCmd(opcodes, 0x3f, "CCF").load("A").alu("CCF").store("A");

        for (Entry<Integer, String> t : indexedList(0x40, 0x08, "B", "C", "D", "E", "H", "L", "(HL)", "A")) {
            for (Entry<Integer, String> s : indexedList(t.getKey(), 0x01, "B", "C", "D", "E", "H", "L", "(HL)", "A")) {
                if (s.getKey() == 0x76) {
                    continue;
                }
                regLoad(opcodes, s.getKey(), t.getValue(), s.getValue());
            }
        }

        regCmd(opcodes, 0x76, "HALT");

        for (Entry<Integer, String> o : indexedList(0x80, 0x08, "ADD", "ADC", "SUB", "SBC", "AND", "XOR", "OR", "CP")) {
            for (Entry<Integer, String> t : indexedList(o.getKey(), 0x01, "B", "C", "D", "E", "H", "L", "(HL)", "A")) {
                regCmd(opcodes, t, o.getValue() + " {}").load("A").alu(o.getValue(), t.getValue()).store("A");
            }
        } 

        for (Entry<Integer, String> c : indexedList(0xc0, 0x08, "NZ", "Z", "NC", "C")) {
            regCmd(opcodes, c, "RET {}").extraCycle().proceedIf(c.getValue()).pop().forceFinish().store("PC");
        }

        for (Entry<Integer, String> t : indexedList(0xc1, 0x10, "BC", "DE", "HL", "AF")) {
            regCmd(opcodes, t, "POP {}").pop().store(t.getValue());
        }

        for (Entry<Integer, String> c : indexedList(0xc2, 0x08, "NZ", "Z", "NC", "C")) {
            regCmd(opcodes, c, "JP {},a16").load("a16").proceedIf(c.getValue()).store("PC").extraCycle();
        }

        regCmd(opcodes, 0xc3, "JP a16").load("a16").store("PC").extraCycle();

        for (Entry<Integer, String> c : indexedList(0xc4, 0x08, "NZ", "Z", "NC", "C")) {
            regCmd(opcodes, c, "CALL {},a16").proceedIf(c.getValue()).extraCycle().load("PC").push().load("a16").store("PC");
        }

        for (Entry<Integer, String> t : indexedList(0xc5, 0x10, "BC", "DE", "HL", "AF")) {
            regCmd(opcodes, t, "PUSH {}").extraCycle().load(t.getValue()).push();
        }

        for (Entry<Integer, String> o : indexedList(0xc6, 0x08, "ADD", "ADC", "SUB", "SBC", "AND", "XOR", "OR", "CP")) {
            regCmd(opcodes, o, o.getValue() + " d8").load("A").alu(o.getValue(), "d8").store("A");
        }

        for (int i = 0xc7, j = 0x00; i <= 0xf7; i += 0x10, j += 0x10) {
            regCmd(opcodes, i, String.format("RST %02XH", j)).load("PC").push().forceFinish().loadWord(j).store("PC");
        }

        regCmd(opcodes, 0xc9, "RET").pop().forceFinish().store("PC");

        regCmd(opcodes, 0xcd, "CALL a16").load("PC").extraCycle().push().load("a16").store("PC");

        for (int i = 0xcf, j = 0x08; i <= 0xff; i += 0x10, j += 0x10) {
            regCmd(opcodes, i, String.format("RST %02XH", j)).load("PC").push().forceFinish().loadWord(j).store("PC");
        }

        regCmd(opcodes, 0xd9, "RETI").pop().forceFinish().store("PC").switchInterrupts(true, false);

        regLoad(opcodes, 0xe2, "(C)", "A");
        regLoad(opcodes, 0xf2, "A", "(C)");

        regCmd(opcodes, 0xe9, "JP (HL)").load("HL").store("PC");

        regCmd(opcodes, 0xe0, "LDH (a8),A").copyByte("(a8)", "A");
        regCmd(opcodes, 0xf0, "LDH A,(a8)").copyByte("A", "(a8)");

        regCmd(opcodes, 0xe8, "ADD SP,r8").load("SP").alu("ADD_SP", "r8").extraCycle().store("SP");
        regCmd(opcodes, 0xf8, "LD HL,SP+r8").load("SP").alu("ADD_SP", "r8").store("HL");

        regLoad(opcodes, 0xea, "(a16)", "A");
        regLoad(opcodes, 0xfa, "A", "(a16)");

        regCmd(opcodes, 0xf3, "DI").switchInterrupts(false, true);
        regCmd(opcodes, 0xfb, "EI").switchInterrupts(true, true);

        regLoad(opcodes, 0xf9, "SP", "HL").extraCycle();

        for (Entry<Integer, String> o : indexedList(0x00, 0x08, "RLC", "RRC", "RL", "RR", "SLA", "SRA", "SWAP", "SRL")) {
            for (Entry<Integer, String> t : indexedList(o.getKey(), 0x01, "B", "C", "D", "E", "H", "L", "(HL)", "A")) {
                regCmd(extOpcodes, t, o.getValue() + " {}").load(t.getValue()).alu(o.getValue()).store(t.getValue());
            }
        }

        for (Entry<Integer, String> o : indexedList(0x40, 0x40, "BIT", "RES", "SET")) {
            for (int b = 0; b < 0x08; b++) {
                for (Entry<Integer, String> t : indexedList(o.getKey() + b * 0x08, 0x01, "B", "C", "D", "E", "H", "L", "(HL)", "A")) {
                    if ("BIT".equals(o.getValue()) && "(HL)".equals(t.getValue())) {
                        regCmd(extOpcodes, t, String.format("BIT %d,(HL)", b)).bitHL(b);
                    } else {
                        regCmd(extOpcodes, t, String.format("%s %d,%s", o.getValue(), b, t.getValue())).load(t.getValue()).alu(o.getValue(), b).store(t.getValue());
                    }
                }
            }
        }

        List<Opcode> commands = new ArrayList<>(0x100);
        List<Opcode> extCommands = new ArrayList<>(0x100);

        for (OpcodeBuilder b : opcodes) {
            if (b == null) {
                commands.add(null);
            } else {
                commands.add(b.build());
            }
        }

        for (OpcodeBuilder b : extOpcodes) {
            if (b == null) {
                extCommands.add(null);
            } else {
                extCommands.add(b.build());
            }
        }

        COMMANDS = Collections.unmodifiableList(commands);
        EXT_COMMANDS = Collections.unmodifiableList(extCommands);
    }

    private Opcodes() {
    }

    private static OpcodeBuilder regLoad(OpcodeBuilder[] commands, int opcode, String target, String source) {
        return regCmd(commands, opcode, String.format("LD %s,%s", target, source)).copyByte(target, source);
    }

    private static OpcodeBuilder regCmd(OpcodeBuilder[] commands, int opcode, String label) {
        if (commands[opcode] != null) {
            throw new IllegalArgumentException(String.format("Opcode %02X already exists: %s", opcode, commands[opcode]));
        }
        OpcodeBuilder builder = new OpcodeBuilder(opcode, label);
        commands[opcode] = builder;
        return builder;
    }

    private static OpcodeBuilder regCmd(OpcodeBuilder[] commands, Entry<Integer, String> opcode, String label) {
        return regCmd(commands, opcode.getKey(), label.replace("{}", opcode.getValue()));
    }

    private static <T> Iterable<Entry<Integer, T>> indexedList(int start, int step, T... values) {
        Map<Integer, T> map = new LinkedHashMap<>();
        int i = start;
        for (T e : values) {
            map.put(i, e);
            i += step;
        }
        return map.entrySet();
    }
}
