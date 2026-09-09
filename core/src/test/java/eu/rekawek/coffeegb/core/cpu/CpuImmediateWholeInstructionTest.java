package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccessProvider;
import org.junit.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

public class CpuImmediateWholeInstructionTest {
    private static final int[] OPCODES = {0x01,0x11,0x21,0x31,0x06,0x0e,0x16,0x1e,0x26,0x2e,0x3e,
            0xc6,0xce,0xd6,0xde,0xe6,0xee,0xf6,0xfe};
    private enum Lane { ORDINARY, DETAILED, LCDC }

    @Test public void allImmediateFormsMatchEveryBudgetPhaseAndReadDotInAllNativeLanes() {
        for (Lane lane:Lane.values()) for (int opcode:OPCODES) for (int pc:new int[]{0,0xff80})
            for (int phase=0;phase<2;phase++) {
                Pair p=new Pair(pc,opcode,0x8f,0xd3,true); p.flags(phase==0?0:0xf0);p.advance(phase);
                var a=p.scalar.captureState();var b=p.batch.captureState();
                var ia=p.scalarInterrupts.captureState();var ib=p.batchInterrupts.captureState();
                for(int budget=1;budget<=54;budget++) {
                    p.scalar.restoreState(a);p.batch.restoreState(b);p.scalarInterrupts.restoreState(ia);p.batchInterrupts.restoreState(ib);
                    p.clear();p.b.inspectWhole=budget==54;
                    int elapsed=p.run(lane,budget);assertEquals("full safe packet",budget,elapsed);p.complete(elapsed);
                    String label=lane+" op="+opcode+" pc="+pc+" phase="+phase+" budget="+budget;
                    p.same(label);p.sameReads(label,pc<0x8000);
                    if(budget==54)assertTrue(label+" must actually fold an immediate operand",p.b.wholeOperandReads>0);
                }
            }
    }

    @Test public void romAndHramOperandRailsFenceWithoutSpeculativeExternalRead() {
        for (Lane lane:new Lane[]{Lane.DETAILED,Lane.LCDC}) for(int opcode:OPCODES)
            for(int pc:new int[]{0x7ffe,0x7fff,0xfffc,0xfffd,0xfffe,0xffff}) for(int phase=0;phase<2;phase++) {
                Pair p=new Pair(pc,opcode,0xa5,0x5a,false);p.advance(phase);
                var a=p.scalar.captureState();var b=p.batch.captureState();
                var ia=p.scalarInterrupts.captureState();var ib=p.batchInterrupts.captureState();
                for(int budget=1;budget<=54;budget++) {
                    p.scalar.restoreState(a);p.batch.restoreState(b);p.scalarInterrupts.restoreState(ia);p.batchInterrupts.restoreState(ib);p.clear();
                    int elapsed=p.run(lane,budget);assertTrue(elapsed<=budget);p.complete(elapsed);
                    String label=lane+" fence op="+opcode+" pc="+pc+" phase="+phase+" budget="+budget;
                    p.same(label);p.sameReads(label,pc<0x8000);
                    assertFalse(label+" VRAM read",p.b.reads.stream().anyMatch(r->r.address==0x8000));
                    assertFalse(label+" accessory read",p.b.reads.stream().anyMatch(r->r.address>=0xfffe));
                    if(budget==54)assertTrue(label+" must stop before external opcode/operand",elapsed<54);
                }
            }
    }

    @Test public void immediateAluCarryHalfCarryZeroAndBorrowVectorsRemainScalarExact() {
        int[] values={0,1,0x0f,0x10,0x7f,0x80,0xff};
        for(int opcode:new int[]{0xc6,0xce,0xd6,0xde,0xe6,0xee,0xf6,0xfe})
            for(int accumulator:values)for(int operand:values)for(int carry=0;carry<2;carry++) {
                Pair p=new Pair(0,opcode,operand,0,false);
                p.scalar.getRegisters().setA(accumulator);p.batch.getRegisters().setA(accumulator);
                p.flags(carry==0?0xe0:0xf0);p.clear();p.b.inspectWhole=true;
                int elapsed=p.run(Lane.DETAILED,4);assertEquals(4,elapsed);p.complete(elapsed);
                String label="ALU "+opcode+" A="+accumulator+" d8="+operand+" C="+carry;
                p.same(label);p.sameReads(label,true);assertEquals(1,p.b.wholeOperandReads);
                assertEquals(0,p.batch.getRegisters().getFlags().getFlagsByte()&0x0f);
            }
    }

    @Test public void restoredPartialOperandsObserveLiveChangesAtRemainingReadDots() {
        for(int opcode:OPCODES)for(int pc:new int[]{0,0xff80})for(int prefix=2;prefix<=5;prefix++)
            for(int budget:new int[]{1,2,3,4,5,23,54}) {
                Pair p=new Pair(pc,opcode,0x8f,0xd3,true);p.advance(prefix);
                if(p.scalar.getState()!=Cpu.State.OPERAND)continue;
                int next=p.scalar.getRegisters().getPC();
                if(!p.a.operands[next])continue;
                var a=p.scalar.captureState();var b=p.batch.captureState();
                p.advance(1);p.scalar.restoreState(a);p.batch.restoreState(b);
                p.a.bytes[next]^=(byte)0x5a;p.b.bytes[next]^=(byte)0x5a;p.clear();
                int elapsed=p.run(Lane.DETAILED,budget);p.complete(elapsed);
                String label="restored op="+opcode+" pc="+pc+" prefix="+prefix+" budget="+budget;
                p.same(label);p.sameReads(label,pc<0x8000);
                p.scalar.restoreState(p.scalar.captureState());p.batch.restoreState(p.batch.captureState());
                p.advance(8);p.same(label+" continuation");
            }
    }

    private record Read(int dot,int address,int value) {}
    private record Write(int address,int value) {}
    private static final class Pair {
        final Memory a=new Memory(),b=new Memory();
        final InterruptManager scalarInterrupts=interrupts(),batchInterrupts=interrupts();
        final Cpu scalar=cpu(a,scalarInterrupts),batch=cpu(b,batchInterrupts);
        Pair(int pc,int opcode,int lo,int hi,boolean repeat) {
            scalar.getRegisters().setPC(pc);batch.getRegisters().setPC(pc);
            for(Cpu c:new Cpu[]{scalar,batch}){c.getRegisters().setA(0x91);c.getRegisters().setBC(0x2468);c.getRegisters().setDE(0x1357);c.getRegisters().setHL(0xffe0);c.getRegisters().setSP(0xdff0);}
            int length=(opcode&0xcf)==0x01?3:2;
            for(int i=0;i<(repeat?64:1);i+=repeat?length:1) {
                put((pc+i)&65535,opcode,false);put((pc+i+1)&65535,lo,true);
                if(length==3)put((pc+i+2)&65535,hi,true);
            }
        }
        void put(int address,int value,boolean operand){a.bytes[address]=b.bytes[address]=(byte)value;a.operands[address]=b.operands[address]=operand;}
        void flags(int flags){scalar.getRegisters().getFlags().setFlagsByte(flags);batch.getRegisters().getFlags().setFlagsByte(flags);}
        void clear(){a.reads.clear();b.reads.clear();a.writes.clear();b.writes.clear();a.dot=b.dot=0;b.wholeOperandReads=0;b.inspectWhole=false;}
        void advance(int ticks){for(int t=0;t<ticks;t++){a.dot=b.dot=t;scalar.tick();batch.tick();}}
        int run(Lane lane,int budget){return switch(lane){case ORDINARY->batch.runPerformanceEpoch(budget);case DETAILED->batch.runNativeCgbDetailedPpuPerformanceEpoch(budget);case LCDC->batch.runNativeCgbLcdcWriteReplayEpoch(budget);};}
        void complete(int ticks){for(int t=0;t<ticks;t++){a.dot=b.dot=t;scalar.tick();batch.replayPerformanceLcdcWritesAtDot(t);}batch.finishPerformanceLcdcWriteReplay();batch.replayPerformanceEpochJournal();}
        void same(String label){assertStateEquals(label+" CPU",scalar.captureState(),batch.captureState());assertStateEquals(label+" interrupts",scalarInterrupts.captureState(),batchInterrupts.captureState());assertArrayEquals(label+" memory",a.bytes,b.bytes);assertEquals(label+" writes",a.writes,b.writes);}
        void sameReads(String label,boolean all){
            if(all)assertEquals(label+" every logical ROM read dot",a.reads,b.reads);
            else assertEquals(label+" every HRAM operand read dot",a.reads.stream().filter(r->a.operands[r.address]).toList(),b.reads.stream().filter(r->b.operands[r.address]).toList());
        }
    }
    private static InterruptManager interrupts(){InterruptManager i=new InterruptManager(true);i.setByte(0xff0f,0);i.consumePpuTickSignals();return i;}
    private static Cpu cpu(Memory m,InterruptManager i){SpeedMode s=new SpeedMode(true);s.setByte(0xff4d,1);assertTrue(s.onStop());Cpu c=new Cpu(m,i,null,s,new Display(false));m.cpu=c;return c;}
    private static final class Memory implements AddressSpace,PerformanceRomAccessProvider,PerformanceRomAccess {
        final byte[]bytes=new byte[65536];final boolean[]operands=new boolean[65536];final List<Read>reads=new ArrayList<>();final List<Write>writes=new ArrayList<>();
        Cpu cpu;int dot,wholeOperandReads;boolean inspectWhole;
        private static final Field ACTIVE=cpuField("performanceEpochActive"),ELAPSED=cpuField("performanceEpochElapsed");
        public boolean accepts(int address){return true;}
        public int getByte(int address){
            int a=address&65535,value=bytes[a]&255,clock=dot;
            try{if(ACTIVE.getBoolean(cpu))clock=ELAPSED.getInt(cpu);}catch(IllegalAccessException e){throw new AssertionError(e);}
            reads.add(new Read(clock,a,value));
            if(inspectWhole&&operands[a])for(StackTraceElement frame:Thread.currentThread().getStackTrace())
                if(frame.getClassName().equals(Cpu.class.getName())&&frame.getMethodName().equals("executePerformanceDirectWholeInstruction")){wholeOperandReads++;break;}
            return value;
        }
        public void setByte(int address,int value){int a=address&65535;bytes[a]=(byte)value;writes.add(new Write(a,value&255));}
        public PerformanceRomAccess acquirePerformanceRomAccess(){return this;}
        public int physicalOffset(int address){return -1;}public int readPhysicalByte(int offset){throw new AssertionError();}
        public int readCpuByte(int address){return getByte(address);}public int peekCpuByte(int address){return bytes[address&65535]&255;}
        private static Field cpuField(String name){try{Field f=Cpu.class.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new AssertionError(e);}}
    }
}
