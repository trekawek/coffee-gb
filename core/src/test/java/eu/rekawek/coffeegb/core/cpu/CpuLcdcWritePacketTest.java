package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccessProvider;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

public class CpuLcdcWritePacketTest {
    @Test public void queuedWritesMatchActualScalarDotsAcrossEveryBudgetAndPhase() {
        for (int pc : new int[]{0,0xff80}) for(int phase=0;phase<2;phase++)
            for(int budget=1;budget<=63;budget++) {
                Memory a=new Memory(),b=new Memory();
                int[] program={0x3e,0x93,0xe0,0x40,0x3e,0xb3,0xe0,0x40,0xc3,pc&255,pc>>>8};
                for(int i=0;i<program.length;i++)a.bytes[pc+i]=b.bytes[pc+i]=(byte)program[i];
                InterruptManager ia=interrupts(),ib=interrupts(); Cpu scalar=cpu(a,ia),batch=cpu(b,ib);
                scalar.getRegisters().setPC(pc);batch.getRegisters().setPC(pc);
                for(int t=0;t<phase;t++){a.dot=b.dot=t;scalar.tick();batch.tick();}
                var save=batch.captureState(); batch.tick();batch.restoreState(save);
                a.writes.clear();b.writes.clear();
                int elapsed=batch.runNativeCgbLcdcWriteReplayEpoch(budget);
                assertEquals(budget,elapsed);
                assertTrue("write must remain unapplied until its replay dot",b.writes.isEmpty());
                for(int t=0;t<elapsed;t++){a.dot=b.dot=t;scalar.tick();batch.replayPerformanceLcdcWritesAtDot(t);}
                batch.finishPerformanceLcdcWriteReplay();
                String label="pc="+pc+" phase="+phase+" budget="+budget;
                assertStateEquals(label,scalar.captureState(),batch.captureState());
                assertStateEquals(label,ia.captureState(),ib.captureState());
                assertArrayEquals(label,a.bytes,b.bytes);assertEquals(label,a.writes,b.writes);
                if(budget==54)assertTrue("one packet must collect several writes",b.writes.size()>=3);
            }
    }
    @Test public void lcdOffOtherMmioAndLifecycleRemainBeforeTheBusCycle() {
        for(int[] p:new int[][]{{0x3e,0x13,0xe0,0x40},{0x3e,0x93,0xe0,0x41},
                {0x3e,0x93,0xe0,0x46},{0x3e,0x93,0xea,0x00,0xc0},
                {0x3e,0x93,0xe0,0x40,0xfb},{0x3e,0x93,0xe0,0x40,0x76}}) {
            Memory a=new Memory(),b=new Memory();for(int i=0;i<p.length;i++)a.bytes[i]=b.bytes[i]=(byte)p[i];
            InterruptManager ia=interrupts(),ib=interrupts();Cpu scalar=cpu(a,ia),batch=cpu(b,ib);
            int elapsed=batch.runNativeCgbLcdcWriteReplayEpoch(54);assertTrue(elapsed<54);
            for(int t=0;t<elapsed;t++){a.dot=b.dot=t;scalar.tick();batch.replayPerformanceLcdcWritesAtDot(t);}
            batch.finishPerformanceLcdcWriteReplay();
            assertStateEquals("fenced CPU",scalar.captureState(),batch.captureState());
            assertArrayEquals(a.bytes,b.bytes);assertEquals(a.writes,b.writes);
            for(int t=0;t<8;t++){a.dot=b.dot=elapsed+t;scalar.tick();batch.tick();}
            assertStateEquals("scalar continuation",scalar.captureState(),batch.captureState());
            assertArrayEquals(a.bytes,b.bytes);assertEquals(a.writes,b.writes);
        }
    }
    @Test public void releasedPrefetchStoresRetainOperandAndWritePhaseAcrossRestore() {
        for(int[] program:new int[][]{{0xe2,0xc3,0,0},{0xe0,0x40,0xc3,0,0},{0xea,0x40,0xff,0xc3,0,0}})
            for(int prefix=0;prefix<=8;prefix++)for(int budget:new int[]{1,2,3,8,23,54,63}){
                Memory a=new Memory(),b=new Memory();for(int n=0;n<program.length;n++)a.bytes[n]=b.bytes[n]=(byte)program[n];
                InterruptManager ia=interrupts(),ib=interrupts();Cpu scalar=cpu(a,ia),batch=cpu(b,ib);
                for(Cpu c:new Cpu[]{scalar,batch}){
                    c.getRegisters().setA(0x93);c.getRegisters().setC(0x40);
                    c.prefetchOpcodeForHdma();c.releaseHdmaPrefetchedOpcode();
                }
                for(int t=0;t<prefix;t++){a.dot=b.dot=t;scalar.tick();batch.tick();}
                batch.restoreState(batch.captureState());a.writes.clear();b.writes.clear();
                int elapsed=batch.runNativeCgbLcdcWriteReplayEpoch(budget);assertEquals(budget,elapsed);
                for(int t=0;t<elapsed;t++){a.dot=b.dot=t;scalar.tick();batch.replayPerformanceLcdcWritesAtDot(t);}
                batch.finishPerformanceLcdcWriteReplay();
                String label="released store="+program[0]+" prefix="+prefix+" budget="+budget;
                assertStateEquals(label,scalar.captureState(),batch.captureState());
                assertStateEquals(label,ia.captureState(),ib.captureState());
                assertArrayEquals(label,a.bytes,b.bytes);assertEquals(label,a.writes,b.writes);
            }
    }
    @Test public void mixedStoresAndLiveHramModificationMatchEveryShortPartition() {
        boolean multi = false;
        for (boolean selfModify:new boolean[]{false,true}) for(int prefix=0;prefix<100;prefix++)
            for(int budget:new int[]{1,2,8,23,54,63}) {
                Memory a=new Memory(),b=new Memory();int pc=selfModify?0xff80:0;
                int[] program=selfModify
                    ?new int[]{0x3e,0x93,0xe0,0x40,0x3e,0xb3,0xe0,0xa0,0xc3,0x9f,0xff}
                    :new int[]{0x0e,0x40,0x3e,0x93,0xe0,0x40,0xee,0x20,0xe2,0xee,0x10,0xea,0x40,0xff,
                              0xf0,0xf0,0xe0,0x40,0x0c,0xe2,0x76};
                for(int n=0;n<program.length;n++)a.bytes[pc+n]=b.bytes[pc+n]=(byte)program[n];
                if(selfModify){int[] next={0x3e,0x93,0xe0,0x40,0x76};
                    for(int n=0;n<next.length;n++)a.bytes[0xff9f+n]=b.bytes[0xff9f+n]=(byte)next[n];}
                a.bytes[0xfff0]=b.bytes[0xfff0]=(byte)0xf3;
                InterruptManager ia=interrupts(),ib=interrupts();Cpu scalar=cpu(a,ia),batch=cpu(b,ib);
                scalar.getRegisters().setPC(pc);batch.getRegisters().setPC(pc);
                for(int t=0;t<prefix;t++){a.dot=b.dot=t;scalar.tick();batch.tick();}
                batch.restoreState(batch.captureState());a.writes.clear();b.writes.clear();
                int elapsed=batch.runNativeCgbLcdcWriteReplayEpoch(budget);
                for(int t=0;t<elapsed;t++){a.dot=b.dot=t;scalar.tick();batch.replayPerformanceLcdcWritesAtDot(t);}
                batch.finishPerformanceLcdcWriteReplay();
                String label="mixed="+selfModify+" prefix="+prefix+" budget="+budget;
                assertStateEquals(label,scalar.captureState(),batch.captureState());
                assertStateEquals(label,ia.captureState(),ib.captureState());assertArrayEquals(label,a.bytes,b.bytes);
                var expected=a.writes.stream().filter(w->w.contains(":65344:")).toList();
                var actual=b.writes.stream().filter(w->w.contains(":65344:")).toList();
                assertEquals(label,expected,actual);multi|=actual.size()>=3;
                for(int t=0;t<8;t++){a.dot=b.dot=elapsed+t;scalar.tick();batch.tick();}
                assertStateEquals(label+" continuation",scalar.captureState(),batch.captureState());
                assertArrayEquals(label,a.bytes,b.bytes);
            }
        assertTrue("mixed E0/E2/EA values must coexist in one packet",multi);
    }
    @Test public void restoreDiscardsUnpublishedTimelineAtBothEpochMaxima() {
        for (int budget : new int[]{54, 63}) {
            Memory memory=new Memory();int[] p={0x3e,0x93,0xe0,0x40,0xc3,0,0};
            for(int n=0;n<p.length;n++)memory.bytes[n]=(byte)p[n];
            Cpu c=cpu(memory,interrupts());var before=c.captureState();
            assertEquals(budget,c.runNativeCgbLcdcWriteReplayEpoch(budget));
            assertTrue(memory.writes.isEmpty());
            c.restoreState(before);
            for(int dot=0;dot<budget;dot++)
                assertEquals(0,c.replayPerformanceLcdcWritesAtDot(dot));
            assertTrue(memory.writes.isEmpty());
            assertStateEquals("restore owns no pending timeline budget="+budget,
                    before,c.captureState());
        }
    }
    static InterruptManager interrupts(){InterruptManager i=new InterruptManager(true);i.setByte(0xff0f,0);i.consumePpuTickSignals();return i;}
    static Cpu cpu(Memory m,InterruptManager i){SpeedMode s=new SpeedMode(true);s.setByte(0xff4d,1);assertTrue(s.onStop());return new Cpu(m,i,null,s,new Display(false));}
    static final class Memory implements AddressSpace,PerformanceRomAccessProvider,PerformanceRomAccess{
        byte[] bytes=new byte[65536];int dot;List<String>writes=new ArrayList<>();
        public boolean accepts(int a){return true;}public int getByte(int a){return bytes[a&65535]&255;}
        public void setByte(int a,int v){bytes[a&65535]=(byte)v;writes.add(dot+":"+(a&65535)+":"+(v&255));}
        public PerformanceRomAccess acquirePerformanceRomAccess(){return this;}
        public int physicalOffset(int a){return -1;}public int readPhysicalByte(int o){throw new AssertionError();}
        public int readCpuByte(int a){return getByte(a);}public int peekCpuByte(int a){return getByte(a);}
    }
}
