package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.cpu.opcode.Opcode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

public class CpuOpcodeMetadataTest {
    @Test public void baseTierMatchesUnchangedHandlerAndWholeCyclesMatchScalarRetirement() throws Exception {
        Field tableField=Cpu.class.getDeclaredField("PERFORMANCE_DIRECT_OPCODE_INFO");tableField.setAccessible(true);
        byte[] table=(byte[])tableField.get(null);assertEquals(256,table.length);
        Method set=Cpu.class.getDeclaredMethod("setCurrentOpcode",Opcode.class);set.setAccessible(true);
        Method base=Cpu.class.getDeclaredMethod("executePerformanceDirectBaseOpcode",int.class);base.setAccessible(true);
        Method cycles=Cpu.class.getDeclaredMethod("performanceDirectWholeMachineCycles",int.class);cycles.setAccessible(true);
        int baseCount=0,wholeCount=0;
        for(int opcode=0;opcode<256;opcode++) {
            CpuLcdcWritePacketTest.Memory memory=new CpuLcdcWritePacketTest.Memory();
            Cpu cpu=CpuLcdcWritePacketTest.cpu(memory,CpuLcdcWritePacketTest.interrupts());
            Opcode decoded=Opcodes.COMMANDS.get(opcode);
            if(decoded==null){assertEquals("illegal opcode must fail closed: "+opcode,0,table[opcode]);continue;}
            set.invoke(cpu,decoded);
            boolean direct=(boolean)base.invoke(cpu,opcode);
            assertEquals("metadata must match original handler for "+opcode,direct,(table[opcode]&8)!=0);
            if(direct)baseCount++;
            if((table[opcode]&7)==0)continue;
            wholeCount++;
            for(int flags=0;flags<256;flags+=16) {
                CpuLcdcWritePacketTest.Memory m=new CpuLcdcWritePacketTest.Memory();
                m.bytes[0]=(byte)opcode;m.bytes[1]=(byte)0xf0;m.bytes[2]=(byte)0xff;
                Cpu scalar=CpuLcdcWritePacketTest.cpu(m,CpuLcdcWritePacketTest.interrupts());
                configure(scalar,flags);int ticks=0;
                do{scalar.tick();ticks++;assertTrue("bounded supported instruction "+opcode,ticks<=12);}while(ticks<2||scalar.getState()!=Cpu.State.OPCODE);
                cpu.getRegisters().getFlags().setFlagsByte(flags);
                assertEquals("scalar cycle count op="+opcode+" F="+flags,ticks/2,(int)cycles.invoke(cpu,opcode));
            }
        }
        assertTrue("base coverage is substantial",baseCount>100);
        assertTrue("whole coverage is substantial",wholeCount>60);
        for(int opcode:new int[]{0x10,0x76,0xcb,0xf3,0xfb,0xd9,0xc9,0xcd,0xc5,0xc1})
            assertEquals("lifecycle/stack/extended retains original sequencer",0,table[opcode]);
    }

    @Test public void allOpcodesMatchEveryBudgetClockPhaseFlagsAndRestoredContinuation() {
        for(int opcode=0;opcode<256;opcode++)for(int flags:new int[]{0,0x10,0x80,0xf0})
            for(int phase=0;phase<2;phase++)for(int budget=1;budget<=54;budget++)
                compare(opcode,flags,phase,0,budget);
    }

    @Test public void partialOperandsAndLifecycleEntriesRemainCanonicalAcrossRestore() {
        for(int opcode:new int[]{0x01,0x21,0x3e,0xc6,0xce,0xd6,0xde,0x18,0x20,0x28,0x30,0x38,
                0xc3,0xc2,0xca,0xd2,0xda,0xe0,0xe2,0xea,0x10,0x76,0xf3,0xfb,0xd9,0xc9,0xcd,0xc5,0xc1,0xcb})
            for(int flags:new int[]{0,0x10,0x80,0xf0})for(int prefix=0;prefix<=10;prefix++)
                for(int budget:new int[]{1,2,3,5,8,23,54})compare(opcode,flags,0,prefix,budget);
    }

    private static void configure(Cpu c,int flags) {
        c.getRegisters().setA(0x93);c.getRegisters().setBC(0xc000);c.getRegisters().setDE(0xc100);
        c.getRegisters().setHL(0xc200);c.getRegisters().setSP(0xfff0);c.getRegisters().getFlags().setFlagsByte(flags);
    }
    private static void compare(int opcode,int flags,int phase,int prefix,int budget) {
        CpuLcdcWritePacketTest.Memory a=new CpuLcdcWritePacketTest.Memory(),b=new CpuLcdcWritePacketTest.Memory();
        for(var m:new CpuLcdcWritePacketTest.Memory[]{a,b}){
            m.bytes[0]=(byte)opcode;m.bytes[1]=(byte)0xf0;m.bytes[2]=(byte)0xff;
            m.bytes[0xc000]=0x35;m.bytes[0xc100]=(byte)0xae;m.bytes[0xc200]=(byte)0xff;
        }
        InterruptManager ia=CpuLcdcWritePacketTest.interrupts(),ib=CpuLcdcWritePacketTest.interrupts();
        Cpu scalar=CpuLcdcWritePacketTest.cpu(a,ia),batch=CpuLcdcWritePacketTest.cpu(b,ib);configure(scalar,flags);configure(batch,flags);
        for(int n=0;n<phase+prefix;n++){scalar.tick();batch.tick();}
        batch.restoreState(batch.captureState());
        int elapsed=batch.runPerformanceEpoch(budget);assertTrue(elapsed>=0&&elapsed<=budget);
        for(int n=0;n<elapsed;n++)scalar.tick();batch.replayPerformanceEpochJournal();
        String label="op="+opcode+" flags="+flags+" phase="+phase+" prefix="+prefix+" budget="+budget;
        assertStateEquals(label,scalar.captureState(),batch.captureState());
        assertStateEquals(label,ia.captureState(),ib.captureState());assertArrayEquals(label,a.bytes,b.bytes);
        for(int n=0;n<8;n++){scalar.tick();batch.tick();}
        assertStateEquals(label+" tail",scalar.captureState(),batch.captureState());
        assertStateEquals(label+" IRQ tail",ia.captureState(),ib.captureState());assertArrayEquals(label+" memory tail",a.bytes,b.bytes);
    }
}
