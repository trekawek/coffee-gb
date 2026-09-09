package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

public class CpuLcdcWholeStoreTest {
    @Test public void allStoresKeepExactQueueDotsAtEveryBudgetAndRestoredPrefix() {
        for(int opcode:new int[]{0xe0,0xe2,0xea})for(int phase=0;phase<2;phase++)
            for(int prefix=0;prefix<=8;prefix++)for(int budget=1;budget<=54;budget++)
                compare(opcode,0xff40,0x93,0,phase,prefix,budget,new int[]{0x3e,0xb3,0xe0,0x40,0xc3,0,0});
    }
    @Test public void wrongAddressesAndLcdOffValuesFenceAtCanonicalDataBoundary() {
        for(int opcode:new int[]{0xe0,0xe2,0xea})
            for(int address:new int[]{0x2000,0xc000,0xff0f,0xff40,0xff41,0xff46,0xff55,0xff80,0xfffd,0xfffe,0xffff}) {
                if(opcode!=0xea&&address<0xff00)continue;
                for(int value:new int[]{0x13,0x93})for(int phase=0;phase<2;phase++)
                    for(int budget=1;budget<=54;budget++)
                        compare(opcode,address,value,0,phase,0,budget,new int[]{0x76});
            }
    }
    @Test public void operandAddressEdgesAndLifecycleContinuationsRetainScalarProof() {
        for(int opcode:new int[]{0xe0,0xe2,0xea})for(int pc:new int[]{0x7ffd,0x7ffe,0x7fff,0xfffa,0xfffc,0xfffd})
            for(int phase=0;phase<2;phase++)for(int budget=1;budget<=12;budget++)
                compare(opcode,0xff40,0x93,pc,phase,0,budget,new int[]{});
        for(int opcode:new int[]{0xe0,0xe2,0xea})for(int lifecycle:new int[]{0xf3,0xfb,0x76,0x10,0xd9})
            for(int prefix=0;prefix<=12;prefix++)for(int budget:new int[]{1,2,3,5,8,23,54})
                compare(opcode,0xff40,0x93,0,0,prefix,budget,new int[]{lifecycle,0});
    }
    @Test public void wholeOperandFetchIsRealAndStorePermissionDoesNotLeak() {
        for(int opcode:new int[]{0xe0,0xea}) {
            TracedMemory m=new TracedMemory();m.bytes[0]=(byte)opcode;m.bytes[1]=0x40;
            if(opcode==0xea)m.bytes[2]=(byte)0xff;
            int length=opcode==0xea?3:2;m.bytes[length]=0x76;
            Cpu cpu=newCpu(m,interrupts());cpu.getRegisters().setA(0x93);
            int elapsed=cpu.runNativeCgbLcdcWriteReplayEpoch(54);
            assertTrue("existing whole handler must fetch the operands",m.wholeReads>0);
            assertEquals(opcode==0xea?2:1,m.wholeReads);
            assertTrue(m.writes.isEmpty());for(int dot=0;dot<elapsed;dot++){m.dot=dot;cpu.replayPerformanceLcdcWritesAtDot(dot);}
            cpu.finishPerformanceLcdcWriteReplay();assertEquals(1,m.writes.size());
        }
        for(boolean priorTimeline:new boolean[]{false,true}) {
            TracedMemory m=new TracedMemory();m.bytes[0]=(byte)0xe0;m.bytes[1]=0x40;
            m.bytes[2]=(byte)0xe0;m.bytes[3]=0x40;
            Cpu cpu=newCpu(m,interrupts());cpu.getRegisters().setA(0x93);
            if(priorTimeline){int n=cpu.runNativeCgbLcdcWriteReplayEpoch(6);
                for(int dot=0;dot<n;dot++){m.dot=dot;cpu.replayPerformanceLcdcWritesAtDot(dot);}
                cpu.finishPerformanceLcdcWriteReplay();m.writes.clear();}
            assertTrue(cpu.runPerformanceEpoch(54)>0);assertTrue("ordinary bus still journals FF40",cpu.hasPerformanceEpochJournal());
            assertTrue(m.writes.isEmpty());assertTrue(cpu.replayPerformanceEpochJournal());assertEquals(1,m.writes.size());
            assertFalse(cpu.replayPerformanceEpochJournal());assertEquals(0,cpu.replayPerformanceLcdcWritesAtDot(0));
        }
    }

    private static void compare(int opcode,int address,int value,int pc,int phase,int prefix,int budget,int[] tail) {
        CpuLcdcWritePacketTest.Memory a=new CpuLcdcWritePacketTest.Memory(),b=new CpuLcdcWritePacketTest.Memory();
        int length=opcode==0xea?3:opcode==0xe0?2:1;
        int[] code=new int[length+tail.length];code[0]=opcode;if(length>=2)code[1]=address&255;
        if(length==3)code[2]=address>>>8;System.arraycopy(tail,0,code,length,tail.length);
        for(int n=0;n<code.length;n++)a.bytes[(pc+n)&65535]=b.bytes[(pc+n)&65535]=(byte)code[n];
        InterruptManager ia=interrupts(),ib=interrupts();Cpu scalar=CpuLcdcWritePacketTest.cpu(a,ia),batch=CpuLcdcWritePacketTest.cpu(b,ib);
        for(Cpu c:new Cpu[]{scalar,batch}){c.getRegisters().setPC(pc);c.getRegisters().setA(value);c.getRegisters().setC(address&255);c.getRegisters().setSP(0xfff0);}
        for(int n=0;n<phase+prefix;n++){a.dot=b.dot=n;scalar.tick();batch.tick();}
        batch.restoreState(batch.captureState());a.writes.clear();b.writes.clear();
        int elapsed=batch.runNativeCgbLcdcWriteReplayEpoch(budget);
        for(String write:b.writes) {
            int target=Integer.parseInt(write.split(":")[1]);
            assertTrue("only canonical HRAM writes may precede timeline replay: "+write,
                    target>=0xff80&&target<0xfffe);
        }
        for(int n=0;n<elapsed;n++){a.dot=b.dot=n;scalar.tick();batch.replayPerformanceLcdcWritesAtDot(n);}
        batch.finishPerformanceLcdcWriteReplay();
        String label="op="+opcode+" address="+address+" value="+value+" pc="+pc+" prefix="+prefix+" phase="+phase+" budget="+budget;
        assertStateEquals(label,scalar.captureState(),batch.captureState());assertStateEquals(label,ia.captureState(),ib.captureState());
        assertArrayEquals(label,a.bytes,b.bytes);
        assertEquals(label,a.writes.stream().filter(w->w.contains(":65344:")).toList(),b.writes.stream().filter(w->w.contains(":65344:")).toList());
        for(int n=0;n<12;n++){a.dot=b.dot=elapsed+n;scalar.tick();batch.tick();}
        assertStateEquals(label+" continuation",scalar.captureState(),batch.captureState());assertArrayEquals(label,a.bytes,b.bytes);
    }
    private static InterruptManager interrupts(){return CpuLcdcWritePacketTest.interrupts();}
    private static Cpu newCpu(AddressSpace memory,InterruptManager irq){SpeedMode speed=new SpeedMode(true);speed.setByte(0xff4d,1);assertTrue(speed.onStop());return new Cpu(memory,irq,null,speed,new Display(false));}
    private static final class TracedMemory implements AddressSpace,PerformanceRomAccessProvider,PerformanceRomAccess {
        final byte[] bytes=new byte[65536];final List<String>writes=new ArrayList<>();int dot,wholeReads;
        public boolean accepts(int a){return true;}
        public int getByte(int a){if(StackWalker.getInstance().walk(s->s.anyMatch(f->f.getMethodName().equals("executePerformanceDirectWholeInstruction"))))wholeReads++;return bytes[a&65535]&255;}
        public void setByte(int a,int v){bytes[a&65535]=(byte)v;writes.add(dot+":"+a+":"+v);}
        public PerformanceRomAccess acquirePerformanceRomAccess(){return this;}public int physicalOffset(int a){return -1;}
        public int readPhysicalByte(int p){throw new AssertionError();}public int readCpuByte(int a){return getByte(a);}
        public int peekCpuByte(int a){return bytes[a&65535]&255;}
    }
}
