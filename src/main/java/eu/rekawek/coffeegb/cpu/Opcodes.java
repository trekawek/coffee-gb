package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;

import java.util.List;

import static eu.rekawek.coffeegb.cpu.BitUtils.abs;
import static eu.rekawek.coffeegb.cpu.BitUtils.checkByteArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.checkWordArgument;
import static eu.rekawek.coffeegb.cpu.BitUtils.clearBit;
import static eu.rekawek.coffeegb.cpu.BitUtils.getLSB;
import static eu.rekawek.coffeegb.cpu.BitUtils.getMSB;
import static eu.rekawek.coffeegb.cpu.BitUtils.isNegative;
import static eu.rekawek.coffeegb.cpu.BitUtils.setBit;
import static eu.rekawek.coffeegb.cpu.BitUtils.toWord;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

public final class Opcodes {

    public static final List<Command> COMMANDS;

    public static final List<Command> EXT_COMMANDS;

    static {
        Command[] commands = new Command[0x100];
        Command[] extCommands = new Command[0x100];

        regCmd(commands, 0x06, 8, 1, "LD B, n", (r, m, a) -> r.setB(a[0]));
        regCmd(commands, 0x0e, 8, 1, "LD C, n", (r, m, a) -> r.setC(a[0]));
        regCmd(commands, 0x16, 8, 1, "LD D, n", (r, m, a) -> r.setD(a[0]));
        regCmd(commands, 0x1e, 8, 1, "LD E, n", (r, m, a) -> r.setE(a[0]));
        regCmd(commands, 0x26, 8, 1, "LD H, n", (r, m, a) -> r.setH(a[0]));
        regCmd(commands, 0x2e, 8, 1, "LD L, n", (r, m, a) -> r.setL(a[0]));

        regCmd(commands, 0x7f, 4, 0, "LD A, A", (r, m, a) -> r.setA(r.getA()));
        regCmd(commands, 0x78, 4, 0, "LD A, B", (r, m, a) -> r.setA(r.getB()));
        regCmd(commands, 0x79, 4, 0, "LD A, C", (r, m, a) -> r.setA(r.getC()));
        regCmd(commands, 0x7A, 4, 0, "LD A, D", (r, m, a) -> r.setA(r.getD()));
        regCmd(commands, 0x7B, 4, 0, "LD A, E", (r, m, a) -> r.setA(r.getE()));
        regCmd(commands, 0x7C, 4, 0, "LD A, H", (r, m, a) -> r.setA(r.getH()));
        regCmd(commands, 0x7D, 4, 0, "LD A, L", (r, m, a) -> r.setA(r.getL()));
        regCmd(commands, 0x7E, 8, 0, "LD A, (HL)", (r, m, a) -> r.setA(m.getByte(r.getHL())));

        regCmd(commands, 0x40, 4, 0, "LD B, B", (r, m, a) -> r.setB(r.getB()));
        regCmd(commands, 0x41, 4, 0, "LD B, C", (r, m, a) -> r.setB(r.getC()));
        regCmd(commands, 0x42, 4, 0, "LD B, D", (r, m, a) -> r.setB(r.getD()));
        regCmd(commands, 0x43, 4, 0, "LD B, E", (r, m, a) -> r.setB(r.getE()));
        regCmd(commands, 0x44, 4, 0, "LD B, H", (r, m, a) -> r.setB(r.getH()));
        regCmd(commands, 0x45, 4, 0, "LD B, L", (r, m, a) -> r.setB(r.getL()));
        regCmd(commands, 0x46, 8, 0, "LD B, (HL)", (r, m, a) -> r.setB(m.getByte(r.getHL())));

        regCmd(commands, 0x48, 4, 0, "LD C, B", (r, m, a) -> r.setC(r.getB()));
        regCmd(commands, 0x49, 4, 0, "LD C, C", (r, m, a) -> r.setC(r.getC()));
        regCmd(commands, 0x4a, 4, 0, "LD C, D", (r, m, a) -> r.setC(r.getD()));
        regCmd(commands, 0x4b, 4, 0, "LD C, E", (r, m, a) -> r.setC(r.getE()));
        regCmd(commands, 0x4c, 4, 0, "LD C, H", (r, m, a) -> r.setC(r.getH()));
        regCmd(commands, 0x4d, 4, 0, "LD C, L", (r, m, a) -> r.setC(r.getL()));
        regCmd(commands, 0x4e, 8, 0, "LD C, (HL)", (r, m, a) -> r.setC(m.getByte(r.getHL())));

        regCmd(commands, 0x50, 4, 0, "LD D, B", (r, m, a) -> r.setD(r.getB()));
        regCmd(commands, 0x51, 4, 0, "LD D, C", (r, m, a) -> r.setD(r.getC()));
        regCmd(commands, 0x52, 4, 0, "LD D, D", (r, m, a) -> r.setD(r.getD()));
        regCmd(commands, 0x53, 4, 0, "LD D, E", (r, m, a) -> r.setD(r.getE()));
        regCmd(commands, 0x54, 4, 0, "LD D, H", (r, m, a) -> r.setD(r.getH()));
        regCmd(commands, 0x55, 4, 0, "LD D, L", (r, m, a) -> r.setD(r.getL()));
        regCmd(commands, 0x56, 8, 0, "LD D, (HL)", (r, m, a) -> r.setD(m.getByte(r.getHL())));

        regCmd(commands, 0x58, 4, 0, "LD E, B", (r, m, a) -> r.setE(r.getB()));
        regCmd(commands, 0x59, 4, 0, "LD E, C", (r, m, a) -> r.setE(r.getC()));
        regCmd(commands, 0x5a, 4, 0, "LD E, D", (r, m, a) -> r.setE(r.getD()));
        regCmd(commands, 0x5b, 4, 0, "LD E, E", (r, m, a) -> r.setE(r.getE()));
        regCmd(commands, 0x5c, 4, 0, "LD E, H", (r, m, a) -> r.setE(r.getH()));
        regCmd(commands, 0x5d, 4, 0, "LD E, L", (r, m, a) -> r.setE(r.getL()));
        regCmd(commands, 0x5e, 8, 0, "LD E, (HL)", (r, m, a) -> r.setE(m.getByte(r.getHL())));

        regCmd(commands, 0x60, 4, 0, "LD H, B", (r, m, a) -> r.setH(r.getB()));
        regCmd(commands, 0x61, 4, 0, "LD H, C", (r, m, a) -> r.setH(r.getC()));
        regCmd(commands, 0x62, 4, 0, "LD H, D", (r, m, a) -> r.setH(r.getD()));
        regCmd(commands, 0x63, 4, 0, "LD H, E", (r, m, a) -> r.setH(r.getE()));
        regCmd(commands, 0x64, 4, 0, "LD H, H", (r, m, a) -> r.setH(r.getH()));
        regCmd(commands, 0x65, 4, 0, "LD H, L", (r, m, a) -> r.setH(r.getL()));
        regCmd(commands, 0x66, 8, 0, "LD H, (HL)", (r, m, a) -> r.setH(m.getByte(r.getHL())));

        regCmd(commands, 0x68, 4, 0, "LD L, B", (r, m, a) -> r.setL(r.getB()));
        regCmd(commands, 0x69, 4, 0, "LD L, C", (r, m, a) -> r.setL(r.getC()));
        regCmd(commands, 0x6a, 4, 0, "LD L, D", (r, m, a) -> r.setL(r.getD()));
        regCmd(commands, 0x6b, 4, 0, "LD L, E", (r, m, a) -> r.setL(r.getE()));
        regCmd(commands, 0x6c, 4, 0, "LD L, H", (r, m, a) -> r.setL(r.getH()));
        regCmd(commands, 0x6d, 4, 0, "LD L, L", (r, m, a) -> r.setL(r.getL()));
        regCmd(commands, 0x6e, 8, 0, "LD L, (HL)", (r, m, a) -> r.setL(m.getByte(r.getHL())));

        regCmd(commands, 0x70, 8, 0, "LD (HL), B", (r, m, a) -> m.setByte(r.getHL(), r.getB()));
        regCmd(commands, 0x71, 8, 0, "LD (HL), C", (r, m, a) -> m.setByte(r.getHL(), r.getC()));
        regCmd(commands, 0x72, 8, 0, "LD (HL), D", (r, m, a) -> m.setByte(r.getHL(), r.getD()));
        regCmd(commands, 0x73, 8, 0, "LD (HL), E", (r, m, a) -> m.setByte(r.getHL(), r.getE()));
        regCmd(commands, 0x74, 8, 0, "LD (HL), H", (r, m, a) -> m.setByte(r.getHL(), r.getH()));
        regCmd(commands, 0x75, 8, 0, "LD (HL), L", (r, m, a) -> m.setByte(r.getHL(), r.getL()));
        regCmd(commands, 0x36, 12, 1, "LD (HL), n", (r, m, a) -> m.setByte(r.getHL(), a[0]));

        regCmd(commands, 0x7f, 4, 0, "LD A, A", (r, m, a) -> r.setA(r.getA()));
        regCmd(commands, 0x78, 4, 0, "LD A, B", (r, m, a) -> r.setA(r.getB()));
        regCmd(commands, 0x79, 4, 0, "LD A, C", (r, m, a) -> r.setA(r.getC()));
        regCmd(commands, 0x7a, 4, 0, "LD A, D", (r, m, a) -> r.setA(r.getD()));
        regCmd(commands, 0x7b, 4, 0, "LD A, E", (r, m, a) -> r.setA(r.getE()));
        regCmd(commands, 0x7c, 4, 0, "LD A, H", (r, m, a) -> r.setA(r.getH()));
        regCmd(commands, 0x7d, 4, 0, "LD A, L", (r, m, a) -> r.setA(r.getL()));

        regCmd(commands, 0x0a, 8, 0, "LD A, (BC)", (r, m, a) -> r.setA(m.getByte(r.getBC())));
        regCmd(commands, 0x1a, 8, 0, "LD A, (DE)", (r, m, a) -> r.setA(m.getByte(r.getDE())));
        regCmd(commands, 0x7e, 8, 0, "LD A, (HL)", (r, m, a) -> r.setA(m.getByte(r.getHL())));
        regCmd(commands, 0xfa, 16, 2, "LD A, (nn)", (r, m, a) -> r.setA(m.getByte(toWord(a))));
        regCmd(commands, 0x3e, 16, 1, "LD A, #", (r, m, a) -> r.setA(a[0]));

        regCmd(commands, 0x7f, 4, 0, "LD A, A", (r, m, a) -> r.setA(r.getA()));
        regCmd(commands, 0x47, 4, 0, "LD B, A", (r, m, a) -> r.setB(r.getA()));
        regCmd(commands, 0x4f, 4, 0, "LD C, A", (r, m, a) -> r.setC(r.getA()));
        regCmd(commands, 0x57, 4, 0, "LD D, A", (r, m, a) -> r.setD(r.getA()));
        regCmd(commands, 0x5f, 4, 0, "LD E, A", (r, m, a) -> r.setE(r.getA()));
        regCmd(commands, 0x67, 4, 0, "LD H, A", (r, m, a) -> r.setH(r.getA()));
        regCmd(commands, 0x6f, 4, 0, "LD L, A", (r, m, a) -> r.setL(r.getA()));

        regCmd(commands, 0x02, 8, 0, "LD (BC), A", (r, m, a) -> m.setByte(r.getBC(), r.getA()));
        regCmd(commands, 0x12, 8, 0, "LD (DE), A", (r, m, a) -> m.setByte(r.getDE(), r.getA()));
        regCmd(commands, 0x77, 8, 0, "LD (HL), A", (r, m, a) -> m.setByte(r.getHL(), r.getA()));
        regCmd(commands, 0xea, 16, 2, "LD (nn), A", (r, m, a) -> m.setByte(toWord(a), r.getA()));

        regCmd(commands, 0xf2, 8, 0, "LD A, (C)", (r, m, a) -> r.setA(m.getByte(0xff00 + r.getC())));
        regCmd(commands, 0xe2, 8, 0, "LD (C), A", (r, m, a) -> m.setByte(0xff00 + r.getC(), r.getA()));

        regCmd(commands, 0x3a, 8, 0, "LD A, (HLD)", (r, m, a) -> r.setA(m.getByte(r.decrementHL())));
        regCmd(commands, 0x32, 8, 0, "LD (HLD), A", (r, m, a) -> m.setByte(r.decrementHL(), r.getA()));

        regCmd(commands, 0x2a, 8, 0, "LD A, (HLI)", (r, m, a) -> r.setA(m.getByte(r.incrementHL())));
        regCmd(commands, 0x22, 8, 0, "LD (HLI), A", (r, m, a) -> m.setByte(r.incrementHL(), r.getA()));

        regCmd(commands, 0xe0, 12, 1, "LDH (n), A", (r, m, a) -> m.setByte(0xff00 + a[0], r.getA()));
        regCmd(commands, 0xf0, 12, 1, "LDH A, (n)", (r, m, a) -> r.setA(m.getByte(0xff00 + a[0])));

        regCmd(commands, 0x01, 12, 2, "LD BC, nn", (r, m, a) -> r.setBC(toWord(a)));
        regCmd(commands, 0x11, 12, 2, "LD DE, nn", (r, m, a) -> r.setDE(toWord(a)));
        regCmd(commands, 0x21, 12, 2, "LD HL, nn", (r, m, a) -> r.setHL(toWord(a)));
        regCmd(commands, 0x31, 12, 2, "LD SP, nn", (r, m, a) -> r.setSP(toWord(a)));

        regCmd(commands, 0xf9, 8, 2, "LD SP, HL", (r, m, a) -> r.setSP(r.getHL()));

        regCmd(commands, 0xf8, 12, 1, "LDHL SP, n", (r, m, a) -> r.setHL(addSignedByteToWord(r.getFlags(), r.getSP(), a[0])));

        regCmd(commands, 0x08, 20, 2, "LD (nn), SP", (r, m, a) -> {
            m.setByte(toWord(a), getLSB(r.getSP()));
            m.setByte((toWord(a) + 1) & 0xffff, getMSB(r.getSP()));
        });

        regCmd(commands, 0xf5, 16, 0, "PUSH AF", (r, m, a) -> push(r, m, r.getAF()));
        regCmd(commands, 0xc5, 16, 0, "PUSH BC", (r, m, a) -> push(r, m, r.getBC()));
        regCmd(commands, 0xd5, 16, 0, "PUSH DE", (r, m, a) -> push(r, m, r.getDE()));
        regCmd(commands, 0xe5, 16, 0, "PUSH HL", (r, m, a) -> push(r, m, r.getHL()));

        regCmd(commands, 0xf1, 12, 0, "PUSH AF", (r, m, a) -> r.setAF(pop(r, m)));
        regCmd(commands, 0xc1, 12, 0, "PUSH BC", (r, m, a) -> r.setBC(pop(r, m)));
        regCmd(commands, 0xd1, 12, 0, "PUSH DE", (r, m, a) -> r.setDE(pop(r, m)));
        regCmd(commands, 0xe1, 12, 0, "PUSH HL", (r, m, a) -> r.setHL(pop(r, m)));

        regCmd(commands, 0x87, 4, 0, "ADD A, A", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), r.getA())));
        regCmd(commands, 0x80, 4, 0, "ADD A, B", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), r.getB())));
        regCmd(commands, 0x81, 4, 0, "ADD A, C", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), r.getC())));
        regCmd(commands, 0x82, 4, 0, "ADD A, D", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), r.getD())));
        regCmd(commands, 0x83, 4, 0, "ADD A, E", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), r.getE())));
        regCmd(commands, 0x84, 4, 0, "ADD A, H", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), r.getH())));
        regCmd(commands, 0x85, 4, 0, "ADD A, L", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), r.getL())));
        regCmd(commands, 0x86, 8, 0, "ADD A, (HL)", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), m.getByte(r.getHL()))));
        regCmd(commands, 0xc6, 8, 1, "ADD A, #", (r, m, a) -> r.setA(addBytes(r.getFlags(), r.getA(), a[0])));

        regCmd(commands, 0x8f, 4, 0, "ADC A, A", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), r.getA())));
        regCmd(commands, 0x88, 4, 0, "ADC A, B", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), r.getB())));
        regCmd(commands, 0x89, 4, 0, "ADC A, C", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), r.getC())));
        regCmd(commands, 0x8a, 4, 0, "ADC A, D", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), r.getD())));
        regCmd(commands, 0x8b, 4, 0, "ADC A, E", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), r.getE())));
        regCmd(commands, 0x8c, 4, 0, "ADC A, H", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), r.getH())));
        regCmd(commands, 0x8d, 4, 0, "ADC A, L", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), r.getL())));
        regCmd(commands, 0x8e, 8, 0, "ADC A, (HL)", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), m.getByte(r.getHL()))));
        regCmd(commands, 0xce, 8, 1, "ADC A, #", (r, m, a) -> r.setA(addBytesAndCarry(r.getFlags(), r.getA(), a[0])));

        regCmd(commands, 0x97, 4, 0, "SUB A, A", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), r.getA())));
        regCmd(commands, 0x90, 4, 0, "SUB A, B", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), r.getB())));
        regCmd(commands, 0x91, 4, 0, "SUB A, C", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), r.getC())));
        regCmd(commands, 0x92, 4, 0, "SUB A, D", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), r.getD())));
        regCmd(commands, 0x93, 4, 0, "SUB A, E", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), r.getE())));
        regCmd(commands, 0x94, 4, 0, "SUB A, H", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), r.getH())));
        regCmd(commands, 0x95, 4, 0, "SUB A, L", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), r.getL())));
        regCmd(commands, 0x96, 8, 0, "SUB A, (HL)", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), m.getByte(r.getHL()))));
        regCmd(commands, 0xd6, 8, 1, "SUB A, #", (r, m, a) -> r.setA(subBytes(r.getFlags(), r.getA(), a[0])));

        regCmd(commands, 0x9f, 4, 0, "SBC A, A", (r, m, a) -> r.setA(subBytesWithCarry(r.getFlags(), r.getA(), r.getA())));
        regCmd(commands, 0x98, 4, 0, "SBC A, B", (r, m, a) -> r.setA(subBytesWithCarry(r.getFlags(), r.getA(), r.getB())));
        regCmd(commands, 0x99, 4, 0, "SBC A, C", (r, m, a) -> r.setA(subBytesWithCarry(r.getFlags(), r.getA(), r.getC())));
        regCmd(commands, 0x9a, 4, 0, "SBC A, D", (r, m, a) -> r.setA(subBytesWithCarry(r.getFlags(), r.getA(), r.getD())));
        regCmd(commands, 0x9b, 4, 0, "SBC A, E", (r, m, a) -> r.setA(subBytesWithCarry(r.getFlags(), r.getA(), r.getE())));
        regCmd(commands, 0x9c, 4, 0, "SBC A, H", (r, m, a) -> r.setA(subBytesWithCarry(r.getFlags(), r.getA(), r.getH())));
        regCmd(commands, 0x9d, 4, 0, "SBC A, L", (r, m, a) -> r.setA(subBytesWithCarry(r.getFlags(), r.getA(), r.getL())));
        regCmd(commands, 0x9e, 8, 0, "SBC A, (HL)", (r, m, a) -> r.setA(subBytesWithCarry(r.getFlags(), r.getA(), m.getByte(r.getHL()))));

        regCmd(commands, 0xa7, 4, 0, "AND A, A", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), r.getA())));
        regCmd(commands, 0xa0, 4, 0, "AND A, B", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), r.getB())));
        regCmd(commands, 0xa1, 4, 0, "AND A, C", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), r.getC())));
        regCmd(commands, 0xa2, 4, 0, "AND A, D", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), r.getD())));
        regCmd(commands, 0xa3, 4, 0, "AND A, E", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), r.getE())));
        regCmd(commands, 0xa4, 4, 0, "AND A, H", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), r.getH())));
        regCmd(commands, 0xa5, 4, 0, "AND A, L", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), r.getL())));
        regCmd(commands, 0xa6, 8, 0, "AND A, (HL)", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), m.getByte(r.getHL()))));
        regCmd(commands, 0xe6, 8, 1, "AND A, #", (r, m, a) -> r.setA(and(r.getFlags(), r.getA(), a[0])));

        regCmd(commands, 0xb7, 4, 0, "OR A, A", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), r.getA())));
        regCmd(commands, 0xb0, 4, 0, "OR A, B", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), r.getB())));
        regCmd(commands, 0xb1, 4, 0, "OR A, C", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), r.getC())));
        regCmd(commands, 0xb2, 4, 0, "OR A, D", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), r.getD())));
        regCmd(commands, 0xb3, 4, 0, "OR A, E", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), r.getE())));
        regCmd(commands, 0xb4, 4, 0, "OR A, H", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), r.getH())));
        regCmd(commands, 0xb5, 4, 0, "OR A, L", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), r.getL())));
        regCmd(commands, 0xb6, 8, 0, "OR A, (HL)", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), m.getByte(r.getHL()))));
        regCmd(commands, 0xf6, 8, 1, "OR A, #", (r, m, a) -> r.setA(or(r.getFlags(), r.getA(), a[0])));

        regCmd(commands, 0xaf, 4, 0, "XOR A, A", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), r.getA())));
        regCmd(commands, 0xa8, 4, 0, "XOR A, B", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), r.getB())));
        regCmd(commands, 0xa9, 4, 0, "XOR A, C", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), r.getC())));
        regCmd(commands, 0xaa, 4, 0, "XOR A, D", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), r.getD())));
        regCmd(commands, 0xab, 4, 0, "XOR A, E", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), r.getE())));
        regCmd(commands, 0xac, 4, 0, "XOR A, H", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), r.getH())));
        regCmd(commands, 0xad, 4, 0, "XOR A, L", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), r.getL())));
        regCmd(commands, 0xae, 8, 0, "XOR A, (HL)", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), m.getByte(r.getHL()))));
        regCmd(commands, 0xee, 8, 1, "XOR A, #", (r, m, a) -> r.setA(xor(r.getFlags(), r.getA(), a[0])));

        regCmd(commands, 0xbf, 4, 0, "CP A, A", (r, m, a) -> subBytes(r.getFlags(), r.getA(), r.getA()));
        regCmd(commands, 0xb8, 4, 0, "CP A, B", (r, m, a) -> subBytes(r.getFlags(), r.getA(), r.getB()));
        regCmd(commands, 0xb9, 4, 0, "CP A, C", (r, m, a) -> subBytes(r.getFlags(), r.getA(), r.getC()));
        regCmd(commands, 0xba, 4, 0, "CP A, D", (r, m, a) -> subBytes(r.getFlags(), r.getA(), r.getD()));
        regCmd(commands, 0xbb, 4, 0, "CP A, E", (r, m, a) -> subBytes(r.getFlags(), r.getA(), r.getE()));
        regCmd(commands, 0xbc, 4, 0, "CP A, H", (r, m, a) -> subBytes(r.getFlags(), r.getA(), r.getH()));
        regCmd(commands, 0xbd, 4, 0, "CP A, L", (r, m, a) -> subBytes(r.getFlags(), r.getA(), r.getL()));
        regCmd(commands, 0xbe, 8, 0, "CP A, (HL)", (r, m, a) -> subBytes(r.getFlags(), r.getA(), m.getByte(r.getHL())));
        regCmd(commands, 0xfe, 8, 1, "CP A, #", (r, m, a) -> subBytes(r.getFlags(), r.getA(), a[0]));

        regCmd(commands, 0x3c, 4, 0, "INC A", (r, m, a) -> r.setA(inc(r.getFlags(), r.getA())));
        regCmd(commands, 0x04, 4, 0, "INC B", (r, m, a) -> r.setB(inc(r.getFlags(), r.getB())));
        regCmd(commands, 0x0c, 4, 0, "INC C", (r, m, a) -> r.setC(inc(r.getFlags(), r.getC())));
        regCmd(commands, 0x14, 4, 0, "INC D", (r, m, a) -> r.setD(inc(r.getFlags(), r.getD())));
        regCmd(commands, 0x1c, 4, 0, "INC E", (r, m, a) -> r.setE(inc(r.getFlags(), r.getE())));
        regCmd(commands, 0x24, 4, 0, "INC H", (r, m, a) -> r.setH(inc(r.getFlags(), r.getH())));
        regCmd(commands, 0x2c, 4, 0, "INC L", (r, m, a) -> r.setL(inc(r.getFlags(), r.getL())));
        regCmd(commands, 0x34, 12, 0, "INC (HL)", (r, m, a) -> m.setByte(r.getHL(), inc(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(commands, 0x3d, 4, 0, "DEC A", (r, m, a) -> r.setA(dec(r.getFlags(), r.getA())));
        regCmd(commands, 0x05, 4, 0, "DEC B", (r, m, a) -> r.setB(dec(r.getFlags(), r.getB())));
        regCmd(commands, 0x0d, 4, 0, "DEC C", (r, m, a) -> r.setC(dec(r.getFlags(), r.getC())));
        regCmd(commands, 0x15, 4, 0, "DEC D", (r, m, a) -> r.setD(dec(r.getFlags(), r.getD())));
        regCmd(commands, 0x1d, 4, 0, "DEC E", (r, m, a) -> r.setE(dec(r.getFlags(), r.getE())));
        regCmd(commands, 0x25, 4, 0, "DEC H", (r, m, a) -> r.setH(dec(r.getFlags(), r.getH())));
        regCmd(commands, 0x2d, 4, 0, "DEC L", (r, m, a) -> r.setL(dec(r.getFlags(), r.getL())));
        regCmd(commands, 0x35, 12, 0, "DEC (HL)", (r, m, a) -> m.setByte(r.getHL(), dec(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(commands, 0x09, 8, 0, "ADD HL, BC", (r, m, a) -> r.setHL(addWords(r.getFlags(), r.getHL(), r.getBC())));
        regCmd(commands, 0x19, 8, 0, "ADD HL, DE", (r, m, a) -> r.setHL(addWords(r.getFlags(), r.getHL(), r.getDE())));
        regCmd(commands, 0x29, 8, 0, "ADD HL, HL", (r, m, a) -> r.setHL(addWords(r.getFlags(), r.getHL(), r.getHL())));
        regCmd(commands, 0x39, 8, 0, "ADD HL, SP", (r, m, a) -> r.setHL(addWords(r.getFlags(), r.getHL(), r.getSP())));

        regCmd(commands, 0xe8, 16, 1, "ADD SP, #", (r, m, a) -> r.setSP(addSignedByteToWord(r.getFlags(), r.getSP(), a[0])));

        regCmd(commands, 0x03, 8, 0, "INC BC", (r, m, a) -> r.setBC((r.getBC() + 1) & 0xffff));
        regCmd(commands, 0x13, 8, 0, "INC DE", (r, m, a) -> r.setDE((r.getDE() + 1) & 0xffff));
        regCmd(commands, 0x23, 8, 0, "INC HL", (r, m, a) -> r.setHL((r.getHL() + 1) & 0xffff));
        regCmd(commands, 0x33, 8, 0, "INC SP", (r, m, a) -> r.setSP((r.getSP() + 1) & 0xffff));

        regCmd(commands, 0x0b, 8, 0, "DEC BC", (r, m, a) -> r.setBC((r.getBC() - 1) & 0xffff));
        regCmd(commands, 0x1b, 8, 0, "DEC DE", (r, m, a) -> r.setDE((r.getDE() - 1) & 0xffff));
        regCmd(commands, 0x2b, 8, 0, "DEC HL", (r, m, a) -> r.setHL((r.getHL() - 1) & 0xffff));
        regCmd(commands, 0x3b, 8, 0, "DEC SP", (r, m, a) -> r.setSP((r.getSP() - 1) & 0xffff));

        regCmd(extCommands, 0x37, 8, 0, "SWAP A", (r, m, a) -> r.setA(swap(r.getFlags(), r.getA())));
        regCmd(extCommands, 0x30, 8, 0, "SWAP B", (r, m, a) -> r.setB(swap(r.getFlags(), r.getB())));
        regCmd(extCommands, 0x31, 8, 0, "SWAP C", (r, m, a) -> r.setC(swap(r.getFlags(), r.getC())));
        regCmd(extCommands, 0x32, 8, 0, "SWAP D", (r, m, a) -> r.setD(swap(r.getFlags(), r.getD())));
        regCmd(extCommands, 0x33, 8, 0, "SWAP E", (r, m, a) -> r.setE(swap(r.getFlags(), r.getE())));
        regCmd(extCommands, 0x34, 8, 0, "SWAP H", (r, m, a) -> r.setH(swap(r.getFlags(), r.getH())));
        regCmd(extCommands, 0x35, 8, 0, "SWAP L", (r, m, a) -> r.setL(swap(r.getFlags(), r.getL())));
        regCmd(extCommands, 0x36, 16, 0, "SWAP (HL)", (r, m, a) -> m.setByte(r.getHL(), swap(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(commands, 0x27, 4, 0, "DAA", (r, m, a) -> {
            int result = r.getA();
            if ((result & 0x0f) > 9 || r.getFlags().isH()) {
                result += 0x06;
            }
            if ((result & 0xf0) > 0x90 || r.getFlags().isC()) {
                result += 0x60;
                r.getFlags().setC(true);
            }
            result &= 0xff;
            r.getFlags().setZ(result == 0);
            r.getFlags().setH(false);
            r.setA(result);
        });

        regCmd(commands, 0x2f, 4, 0, "CPL", (r, m, a) -> {
            r.getFlags().setN(true);
            r.getFlags().setH(true);
            r.setA((~r.getA()) & 0xff);
        });

        regCmd(commands, 0x3f, 4, 0, "CCF", (r, m, a) -> {
            r.getFlags().setN(false);
            r.getFlags().setH(false);
            r.getFlags().setC(!r.getFlags().isC());
        });

        regCmd(commands, 0x37, 4, 0, "SCF", (r, m, a) -> {
            r.getFlags().setN(false);
            r.getFlags().setH(false);
            r.getFlags().setC(true);
        });

        regCmd(commands, 0x00, 4, 0, "NOP", (r, m, a) -> {});
        regCmd(commands, 0x76, 4, 0, "HALT", (r, m, a) -> {/* TODO */});
        regCmd(commands, 0x10, 4, 1, "STOP", (r, m, a) -> {/* TODO */});
        regCmd(commands, 0xf3, 4, 0, "DI", (r, m, a) -> {/* TODO */});
        regCmd(commands, 0xfb, 4, 0, "EI", (r, m, a) -> {/* TODO */});

        regCmd(commands, 0x07, 4, 0, "RLCA", (r, m, a) -> r.setA(rotateLeft(r.getFlags(), r.getA())));
        regCmd(commands, 0x17, 4, 0, "RLA", (r, m, a) -> r.setA(rotateLeftThroughCarry(r.getFlags(), r.getA())));

        regCmd(commands, 0x0f, 4, 0, "RRCA", (r, m, a) -> r.setA(rotateRight(r.getFlags(), r.getA())));
        regCmd(commands, 0x1f, 4, 0, "RRA", (r, m, a) -> r.setA(rotateRightThroughCarry(r.getFlags(), r.getA())));

        regCmd(extCommands, 0x07, 8, 0, "RLC A", (r, m, a) -> r.setA(rotateLeft(r.getFlags(), r.getA())));
        regCmd(extCommands, 0x00, 8, 0, "RLC B", (r, m, a) -> r.setB(rotateLeft(r.getFlags(), r.getB())));
        regCmd(extCommands, 0x01, 8, 0, "RLC C", (r, m, a) -> r.setC(rotateLeft(r.getFlags(), r.getC())));
        regCmd(extCommands, 0x02, 8, 0, "RLC D", (r, m, a) -> r.setD(rotateLeft(r.getFlags(), r.getD())));
        regCmd(extCommands, 0x03, 8, 0, "RLC E", (r, m, a) -> r.setE(rotateLeft(r.getFlags(), r.getE())));
        regCmd(extCommands, 0x04, 8, 0, "RLC H", (r, m, a) -> r.setH(rotateLeft(r.getFlags(), r.getH())));
        regCmd(extCommands, 0x05, 8, 0, "RLC L", (r, m, a) -> r.setL(rotateLeft(r.getFlags(), r.getL())));
        regCmd(extCommands, 0x06, 16, 0, "RLC (HL)", (r, m, a) -> m.setByte(r.getHL(), rotateLeft(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(extCommands, 0x17, 8, 0, "RL A", (r, m, a) -> r.setA(rotateLeftThroughCarry(r.getFlags(), r.getA())));
        regCmd(extCommands, 0x10, 8, 0, "RL B", (r, m, a) -> r.setB(rotateLeftThroughCarry(r.getFlags(), r.getB())));
        regCmd(extCommands, 0x11, 8, 0, "RL C", (r, m, a) -> r.setC(rotateLeftThroughCarry(r.getFlags(), r.getC())));
        regCmd(extCommands, 0x12, 8, 0, "RL D", (r, m, a) -> r.setD(rotateLeftThroughCarry(r.getFlags(), r.getD())));
        regCmd(extCommands, 0x13, 8, 0, "RL E", (r, m, a) -> r.setE(rotateLeftThroughCarry(r.getFlags(), r.getE())));
        regCmd(extCommands, 0x14, 8, 0, "RL H", (r, m, a) -> r.setH(rotateLeftThroughCarry(r.getFlags(), r.getH())));
        regCmd(extCommands, 0x15, 8, 0, "RL L", (r, m, a) -> r.setL(rotateLeftThroughCarry(r.getFlags(), r.getL())));
        regCmd(extCommands, 0x16, 16, 0, "RL (HL)", (r, m, a) -> m.setByte(r.getHL(), rotateLeftThroughCarry(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(extCommands, 0x0f, 8, 0, "RRC A", (r, m, a) -> r.setA(rotateRight(r.getFlags(), r.getA())));
        regCmd(extCommands, 0x08, 8, 0, "RRC B", (r, m, a) -> r.setB(rotateRight(r.getFlags(), r.getB())));
        regCmd(extCommands, 0x09, 8, 0, "RRC C", (r, m, a) -> r.setC(rotateRight(r.getFlags(), r.getC())));
        regCmd(extCommands, 0x0a, 8, 0, "RRC D", (r, m, a) -> r.setD(rotateRight(r.getFlags(), r.getD())));
        regCmd(extCommands, 0x0b, 8, 0, "RRC E", (r, m, a) -> r.setE(rotateRight(r.getFlags(), r.getE())));
        regCmd(extCommands, 0x0c, 8, 0, "RRC H", (r, m, a) -> r.setH(rotateRight(r.getFlags(), r.getH())));
        regCmd(extCommands, 0x0d, 8, 0, "RRC L", (r, m, a) -> r.setL(rotateRight(r.getFlags(), r.getL())));
        regCmd(extCommands, 0x0e, 16, 0, "RRC (HL)", (r, m, a) -> m.setByte(r.getHL(), rotateRight(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(extCommands, 0x1f, 8, 0, "RR A", (r, m, a) -> r.setA(rotateRightThroughCarry(r.getFlags(), r.getA())));
        regCmd(extCommands, 0x18, 8, 0, "RR B", (r, m, a) -> r.setB(rotateRightThroughCarry(r.getFlags(), r.getB())));
        regCmd(extCommands, 0x19, 8, 0, "RR C", (r, m, a) -> r.setC(rotateRightThroughCarry(r.getFlags(), r.getC())));
        regCmd(extCommands, 0x1a, 8, 0, "RR D", (r, m, a) -> r.setD(rotateRightThroughCarry(r.getFlags(), r.getD())));
        regCmd(extCommands, 0x1b, 8, 0, "RR E", (r, m, a) -> r.setE(rotateRightThroughCarry(r.getFlags(), r.getE())));
        regCmd(extCommands, 0x1c, 8, 0, "RR H", (r, m, a) -> r.setH(rotateRightThroughCarry(r.getFlags(), r.getH())));
        regCmd(extCommands, 0x1d, 8, 0, "RR L", (r, m, a) -> r.setL(rotateRightThroughCarry(r.getFlags(), r.getL())));
        regCmd(extCommands, 0x1e, 16, 0, "RR (HL)", (r, m, a) -> m.setByte(r.getHL(), rotateRightThroughCarry(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(extCommands, 0x27, 8, 0, "SLA A", (r, m, a) -> r.setA(shiftLeft(r.getFlags(), r.getA())));
        regCmd(extCommands, 0x20, 8, 0, "SLA B", (r, m, a) -> r.setB(shiftLeft(r.getFlags(), r.getB())));
        regCmd(extCommands, 0x21, 8, 0, "SLA C", (r, m, a) -> r.setC(shiftLeft(r.getFlags(), r.getC())));
        regCmd(extCommands, 0x22, 8, 0, "SLA D", (r, m, a) -> r.setD(shiftLeft(r.getFlags(), r.getD())));
        regCmd(extCommands, 0x23, 8, 0, "SLA E", (r, m, a) -> r.setE(shiftLeft(r.getFlags(), r.getE())));
        regCmd(extCommands, 0x24, 8, 0, "SLA H", (r, m, a) -> r.setH(shiftLeft(r.getFlags(), r.getH())));
        regCmd(extCommands, 0x25, 8, 0, "SLA L", (r, m, a) -> r.setL(shiftLeft(r.getFlags(), r.getL())));
        regCmd(extCommands, 0x26, 16, 0, "SLA (HL)", (r, m, a) -> m.setByte(r.getHL(), shiftLeft(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(extCommands, 0x2f, 8, 0, "SRA A", (r, m, a) -> r.setA(shiftRightArtithmetic(r.getFlags(), r.getA())));
        regCmd(extCommands, 0x28, 8, 0, "SRA B", (r, m, a) -> r.setB(shiftRightArtithmetic(r.getFlags(), r.getB())));
        regCmd(extCommands, 0x29, 8, 0, "SRA C", (r, m, a) -> r.setC(shiftRightArtithmetic(r.getFlags(), r.getC())));
        regCmd(extCommands, 0x2a, 8, 0, "SRA D", (r, m, a) -> r.setD(shiftRightArtithmetic(r.getFlags(), r.getD())));
        regCmd(extCommands, 0x2b, 8, 0, "SRA E", (r, m, a) -> r.setE(shiftRightArtithmetic(r.getFlags(), r.getE())));
        regCmd(extCommands, 0x2c, 8, 0, "SRA H", (r, m, a) -> r.setH(shiftRightArtithmetic(r.getFlags(), r.getH())));
        regCmd(extCommands, 0x2d, 8, 0, "SRA L", (r, m, a) -> r.setL(shiftRightArtithmetic(r.getFlags(), r.getL())));
        regCmd(extCommands, 0x2e, 16, 0, "SRA (HL)", (r, m, a) -> m.setByte(r.getHL(), shiftRightArtithmetic(r.getFlags(), m.getByte(r.getHL()))));

        regCmd(extCommands, 0x3f, 8, 0, "SRL A", (r, m, a) -> r.setA(shiftRightLogical(r.getFlags(), r.getA())));
        regCmd(extCommands, 0x38, 8, 0, "SRL B", (r, m, a) -> r.setB(shiftRightLogical(r.getFlags(), r.getB())));
        regCmd(extCommands, 0x39, 8, 0, "SRL C", (r, m, a) -> r.setC(shiftRightLogical(r.getFlags(), r.getC())));
        regCmd(extCommands, 0x3a, 8, 0, "SRL D", (r, m, a) -> r.setD(shiftRightLogical(r.getFlags(), r.getD())));
        regCmd(extCommands, 0x3b, 8, 0, "SRL E", (r, m, a) -> r.setE(shiftRightLogical(r.getFlags(), r.getE())));
        regCmd(extCommands, 0x3c, 8, 0, "SRL H", (r, m, a) -> r.setH(shiftRightLogical(r.getFlags(), r.getH())));
        regCmd(extCommands, 0x3d, 8, 0, "SRL L", (r, m, a) -> r.setL(shiftRightLogical(r.getFlags(), r.getL())));
        regCmd(extCommands, 0x3e, 16, 0, "SRL (HL)", (r, m, a) -> m.setByte(r.getHL(), shiftRightLogical(r.getFlags(), m.getByte(r.getHL()))));

        for (int i = 0; i < 8; i++) {
            int bit = i;
            regCmd(extCommands, 0x47 + 0x08 * bit, 8, 0, "BIT " + bit + ", A", (r, m, a) -> bit(r.getFlags(), r.getA(), bit));
            regCmd(extCommands, 0x40 + 0x08 * bit, 8, 0, "BIT " + bit + ", B", (r, m, a) -> bit(r.getFlags(), r.getB(), bit));
            regCmd(extCommands, 0x41 + 0x08 * bit, 8, 0, "BIT " + bit + ", C", (r, m, a) -> bit(r.getFlags(), r.getC(), bit));
            regCmd(extCommands, 0x42 + 0x08 * bit, 8, 0, "BIT " + bit + ", D", (r, m, a) -> bit(r.getFlags(), r.getD(), bit));
            regCmd(extCommands, 0x43 + 0x08 * bit, 8, 0, "BIT " + bit + ", E", (r, m, a) -> bit(r.getFlags(), r.getE(), bit));
            regCmd(extCommands, 0x44 + 0x08 * bit, 8, 0, "BIT " + bit + ", H", (r, m, a) -> bit(r.getFlags(), r.getH(), bit));
            regCmd(extCommands, 0x45 + 0x08 * bit, 8, 0, "BIT " + bit + ", L", (r, m, a) -> bit(r.getFlags(), r.getL(), bit));
            regCmd(extCommands, 0x46 + 0x08 * bit, 16, 0, "BIT " + bit + ", (HL)", (r, m, a) -> bit(r.getFlags(), m.getByte(r.getHL()), bit));

            regCmd(extCommands, 0xc7 + 0x08 * bit, 8, 0, "SET " + bit + ", A", (r, m, a) -> r.setA(setBit(r.getA(), bit)));
            regCmd(extCommands, 0xc0 + 0x08 * bit, 8, 0, "SET " + bit + ", B", (r, m, a) -> r.setA(setBit(r.getB(), bit)));
            regCmd(extCommands, 0xc1 + 0x08 * bit, 8, 0, "SET " + bit + ", C", (r, m, a) -> r.setA(setBit(r.getC(), bit)));
            regCmd(extCommands, 0xc2 + 0x08 * bit, 8, 0, "SET " + bit + ", D", (r, m, a) -> r.setA(setBit(r.getD(), bit)));
            regCmd(extCommands, 0xc3 + 0x08 * bit, 8, 0, "SET " + bit + ", E", (r, m, a) -> r.setA(setBit(r.getE(), bit)));
            regCmd(extCommands, 0xc4 + 0x08 * bit, 8, 0, "SET " + bit + ", H", (r, m, a) -> r.setA(setBit(r.getH(), bit)));
            regCmd(extCommands, 0xc5 + 0x08 * bit, 8, 0, "SET " + bit + ", L", (r, m, a) -> r.setA(setBit(r.getL(), bit)));
            regCmd(extCommands, 0xc6 + 0x08 * bit, 16, 0, "SET " + bit + ", (HL)", (r, m, a) -> m.setByte(r.getHL(), setBit(m.getByte(r.getHL()), bit)));

            regCmd(extCommands, 0x87 + 0x08 * bit, 8, 0, "RES " + bit + ", A", (r, m, a) -> r.setA(clearBit(r.getA(), bit)));
            regCmd(extCommands, 0x80 + 0x08 * bit, 8, 0, "RES " + bit + ", B", (r, m, a) -> r.setA(clearBit(r.getB(), bit)));
            regCmd(extCommands, 0x81 + 0x08 * bit, 8, 0, "RES " + bit + ", C", (r, m, a) -> r.setA(clearBit(r.getC(), bit)));
            regCmd(extCommands, 0x82 + 0x08 * bit, 8, 0, "RES " + bit + ", D", (r, m, a) -> r.setA(clearBit(r.getD(), bit)));
            regCmd(extCommands, 0x83 + 0x08 * bit, 8, 0, "RES " + bit + ", E", (r, m, a) -> r.setA(clearBit(r.getE(), bit)));
            regCmd(extCommands, 0x84 + 0x08 * bit, 8, 0, "RES " + bit + ", H", (r, m, a) -> r.setA(clearBit(r.getH(), bit)));
            regCmd(extCommands, 0x85 + 0x08 * bit, 8, 0, "RES " + bit + ", L", (r, m, a) -> r.setA(clearBit(r.getL(), bit)));
            regCmd(extCommands, 0x86 + 0x08 * bit, 16, 0, "RES " + bit + ", (HL)", (r, m, a) -> m.setByte(r.getHL(), clearBit(m.getByte(r.getHL()), bit)));
        }

        regCmd(commands, 0xc3, 12, 2, "JMP nn", (r, m, a) -> r.setPC(toWord(a)));

        regCmd(commands, 0xc2, 12, 2, "JP NZ, nn", (r, m, a) -> { if(!r.getFlags().isZ()) r.setPC(toWord(a)); });
        regCmd(commands, 0xca, 12, 2, "JP Z, nn",  (r, m, a) -> { if(r.getFlags().isZ())  r.setPC(toWord(a)); });
        regCmd(commands, 0xd2, 12, 2, "JP NC, nn", (r, m, a) -> { if(!r.getFlags().isC()) r.setPC(toWord(a)); });
        regCmd(commands, 0xda, 12, 2, "JP C, nn",  (r, m, a) -> { if(r.getFlags().isC())  r.setPC(toWord(a)); });

        regCmd(commands, 0xe9, 4, 0, "JP (HL)", (r, m, a) -> r.setPC(r.getHL()));
        regCmd(commands, 0x18, 8, 1, "JR n", (r, m, a) -> r.addToPC(a[0]));

        regCmd(commands, 0x20, 8, 1, "JR NZ, n", (r, m, a) -> { if(!r.getFlags().isZ()) r.addToPC(a[0]); });
        regCmd(commands, 0x28, 8, 1, "JR Z, n",  (r, m, a) -> { if(r.getFlags().isZ())  r.addToPC(a[0]); });
        regCmd(commands, 0x30, 8, 1, "JR NC, n", (r, m, a) -> { if(!r.getFlags().isC()) r.addToPC(a[0]); });
        regCmd(commands, 0x38, 8, 1, "JR C, n",  (r, m, a) -> { if(r.getFlags().isC())  r.addToPC(a[0]); });

        regCmd(commands, 0xcd, 12, 2, "CALL nn", (r, m, a) -> call(r, m, toWord(a)));

        regCmd(commands, 0xc4, 12, 2, "CALL NZ, n", (r, m, a) -> { if(!r.getFlags().isZ()) call(r, m, toWord(a)); });
        regCmd(commands, 0xcc, 12, 2, "CALL Z, n",  (r, m, a) -> { if(r.getFlags().isZ())  call(r, m, toWord(a)); });
        regCmd(commands, 0xd4, 12, 2, "CALL NC, n", (r, m, a) -> { if(!r.getFlags().isC()) call(r, m, toWord(a)); });
        regCmd(commands, 0xdc, 12, 2, "CALL C, n",  (r, m, a) -> { if(r.getFlags().isC())  call(r, m, toWord(a)); });

        regCmd(commands, 0xc7, 32, 0, "RST 00H", (r, m, a) -> reset(r, m, 0x00));
        regCmd(commands, 0xcf, 32, 0, "RST 08H", (r, m, a) -> reset(r, m, 0x08));
        regCmd(commands, 0xd7, 32, 0, "RST 10H", (r, m, a) -> reset(r, m, 0x10));
        regCmd(commands, 0xdf, 32, 0, "RST 18H", (r, m, a) -> reset(r, m, 0x18));
        regCmd(commands, 0xe7, 32, 0, "RST 20H", (r, m, a) -> reset(r, m, 0x20));
        regCmd(commands, 0xef, 32, 0, "RST 28H", (r, m, a) -> reset(r, m, 0x28));
        regCmd(commands, 0xf7, 32, 0, "RST 30H", (r, m, a) -> reset(r, m, 0x30));
        regCmd(commands, 0xff, 32, 0, "RST 38H", (r, m, a) -> reset(r, m, 0x38));

        regCmd(commands, 0xc9, 8, 0, "RET", (r, m, a) -> ret(r, m));

        regCmd(commands, 0xc0, 8, 0, "RET NZ", (r, m, a) -> { if(!r.getFlags().isZ()) ret(r, m); });
        regCmd(commands, 0xc8, 8, 0, "RET Z",  (r, m, a) -> { if(r.getFlags().isZ())  ret(r, m); });
        regCmd(commands, 0xd0, 8, 0, "RET NC", (r, m, a) -> { if(!r.getFlags().isC()) ret(r, m); });
        regCmd(commands, 0xd8, 8, 0, "RET C",  (r, m, a) -> { if(r.getFlags().isC())  ret(r, m); });

        regCmd(commands, 0xd9, 8, 0, "RETI", (r, m, a) -> {ret(r, m); /* TODO enabled interrupts */ });

        COMMANDS = unmodifiableList(asList(commands));
        EXT_COMMANDS = unmodifiableList(asList(extCommands));
    }

    private Opcodes() {
    }

    private static void regCmd(Command[] commands, int opcode, int cycles, int argsLength, String label, Command.Operation operation) {
        commands[opcode] = new Command(opcode, cycles, argsLength, label, operation);
    }

    // http://stackoverflow.com/a/7261149/1777058
    private static int addSignedByteToWord(Flags flags, int word, int signedByte) {
        checkWordArgument("w", word);
        checkByteArgument("b", signedByte);

        flags.setZ(false);
        flags.setN(false);

        int b = abs(signedByte);

        if (isNegative(signedByte)) {
            flags.setH((word & 0x0f) < (b & 0x0f));
            flags.setC((word & 0xff) < b);
            return (word - b) % 0xffff;
        } else {
            flags.setC((word & 0xff) + b > 0xff);
            flags.setH((word & 0x0f) + (b & 0x0f) > 0x0f);
            return (word + b) & 0xffff;
        }
    }

    private static int addBytes(Flags flags, int byte1, int byte2) {
        checkByteArgument("byte1", byte1);
        checkByteArgument("byte2", byte2);
        flags.setZ(((byte1 + byte2) & 0xff) == 0);
        flags.setN(false);
        flags.setH((byte1 & 0x0f) + (byte2 & 0x0f) > 0x0f);
        flags.setC(byte1 + byte2 > 0xff);
        return (byte1 + byte2) & 0xff;
    }

    private static int addBytesAndCarry(Flags flags, int byte1, int byte2) {
        checkByteArgument("byte1", byte1);
        checkByteArgument("byte2", byte2);
        int carry = flags.isC() ? 1 : 0;
        flags.setZ(((byte1 + byte2 + carry) & 0xff) == 0);
        flags.setN(false);
        flags.setH((byte1 & 0x0f) + (byte2 & 0x0f) + carry > 0x0f);
        flags.setC(byte1 + byte2 + carry > 0xff);
        return (byte1 + byte2 + carry) & 0xff;
    }

    private static int addWords(Flags flags, int word1, int word2) {
        checkWordArgument("word1", word1);
        checkWordArgument("word2", word2);
        flags.setN(false);
        flags.setH((word1 & 0x0fff) + (word2 & 0x0fff) > 0x0fff);
        flags.setC(word1 + word2 > 0xffff);
        return (word1 + word2) & 0xffff;
    }

    private static int subBytes(Flags flags, int byte1, int byte2) {
        checkByteArgument("byte1", byte1);
        checkByteArgument("byte2", byte2);
        flags.setZ(((byte1 - byte2) & 0xff) == 0);
        flags.setN(true);
        flags.setH((0x0f & byte2) > (0x0f & byte1));
        flags.setC(byte2 > byte1);
        return (byte1 - byte2) % 0xff;
    }

    private static int subBytesWithCarry(Flags flags, int byte1, int byte2) {
        checkByteArgument("byte1", byte1);
        checkByteArgument("byte2", byte2);
        int carry = flags.isC() ? 1 : 0;
        flags.setZ(((byte1 - byte2 - carry) & 0xff) == 0);
        flags.setN(true);
        flags.setH((0x0f & (byte2 + carry)) > (0x0f & byte1));
        flags.setC(byte2 + carry > byte1);
        return (byte1 - byte2 - carry) % 0xff;
    }

    private static int and(Flags flags, int byte1, int byte2) {
        checkByteArgument("byte1", byte1);
        checkByteArgument("byte2", byte2);
        int result = byte1 & byte2;
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(true);
        flags.setC(false);
        return result;
    }

    private static int or(Flags flags, int byte1, int byte2) {
        checkByteArgument("byte1", byte1);
        checkByteArgument("byte2", byte2);
        int result = byte1 | byte2;
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        flags.setC(false);
        return result;
    }

    private static int xor(Flags flags, int byte1, int byte2) {
        checkByteArgument("byte1", byte1);
        checkByteArgument("byte2", byte2);
        int result = byte1 ^ byte2;
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        flags.setC(false);
        return result;
    }

    private static int inc(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = (byteValue + 1) & 0xff;
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH((0x0f & result) < (0x0f & byteValue));
        return result;
    }

    private static int dec(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = (byteValue - 1) & 0xff;
        flags.setZ(result == 0);
        flags.setN(true);
        flags.setH((0x0f & byteValue) == 0);
        return result;
    }

    private static int swap(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int upper = byteValue & 0xf0;
        int lower = byteValue & 0x0f;
        int result = (lower << 4) | (upper >> 4);
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        flags.setC(false);
        return result;
    }

    private static int rotateRight(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = byteValue >> 1;
        if ((byteValue & 1) == 1) {
            result |= (1 << 7);
            flags.setC(true);
        } else {
            flags.setC(false);
        }
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        return result;
    }

    private static int rotateRightThroughCarry(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = byteValue >> 1;
        result |= flags.isC() ? (1 << 7) : 0;
        flags.setC((byteValue & 1) != 0);
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        return result;
    }

    private static int rotateLeft(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = (byteValue << 1) & 0xff;
        if ((byteValue & (1<<7)) != 0) {
            result |= 1;
            flags.setC(true);
        } else {
            flags.setC(false);
        }
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        return result;
    }

    private static int rotateLeftThroughCarry(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = (byteValue << 1) & 0xff;
        result |= flags.isC() ? 1 : 0;
        flags.setC((byteValue & (1<<7)) != 0);
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        return result;
    }

    private static int shiftLeft(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = (byteValue << 1) & 0xff;
        flags.setC((byteValue & (1<<7)) != 0);
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        return result;
    }

    private static int shiftRightArtithmetic(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = (byteValue >> 1) | (byteValue & (1 << 7));
        flags.setC((byteValue & 1) != 0);
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        return result;
    }

    private static int shiftRightLogical(Flags flags, int byteValue) {
        checkByteArgument("byteValue", byteValue);
        int result = (byteValue >> 1);
        flags.setC((byteValue & 1) != 0);
        flags.setZ(result == 0);
        flags.setN(false);
        flags.setH(false);
        return result;
    }

    static void push(Registers registers, AddressSpace addressSpace, int word) {
        checkWordArgument("word", word);
        registers.decrementSP();
        addressSpace.setByte(registers.getSP(), getMSB(word));
        registers.decrementSP();
        addressSpace.setByte(registers.getSP(), getLSB(word));
    }

    private static int pop(Registers registers, AddressSpace addressSpace) {
        int lsb = addressSpace.getByte(registers.getSP());
        registers.incrementSP();
        int msb = addressSpace.getByte(registers.getSP());
        registers.incrementSP();
        return toWord(msb, lsb);
    }

    private static void bit(Flags flags, int byteValue, int bit) {
        checkByteArgument("byteValue", byteValue);
        checkByteArgument("bit", bit);
        flags.setN(false);
        flags.setH(true);
        if (bit < 8) {
            flags.setZ((byteValue & (1 << bit)) != 0);
        }
    }

    private static void call(Registers registers, AddressSpace addressSpace, int address) {
        checkWordArgument("address", address);
        push(registers, addressSpace, (registers.getPC() + 3) & 0xffff);
        registers.setPC(address);
    }

    private static void reset(Registers registers, AddressSpace addressSpace, int address) {
        checkByteArgument("address", address);
        push(registers, addressSpace, registers.getPC());
        registers.setPC(address);
    }

    private static void ret(Registers registers, AddressSpace addressSpace) {
        registers.setPC(pop(registers, addressSpace));
    }
}
