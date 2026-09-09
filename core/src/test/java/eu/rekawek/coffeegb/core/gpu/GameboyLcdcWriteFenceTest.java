package eu.rekawek.coffeegb.core.gpu;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.hardware.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

public class GameboyLcdcWriteFenceTest {
    @Test public void externalReadsWritesAndLifecycleExecuteOnceAfterPacketFence() throws Exception {
        int[][] tails={{0xf0,0x40},{0xf0,0x41},{0xf0,0x44},{0xf0,0x0f},
                {0x3e,0x13,0xe0,0x40},{0x3e,0xc0,0xe0,0x46},{0x3e,0,0xe0,0x55},
                {0x3e,2,0xea,0,0x20},{0xfb},{0xf3},{0x76},{0x10,0},{0xd9}};
        var owner=Gameboy.class.getDeclaredMethod("tryPerformanceLcdcWriteReplayEpoch",long.class);owner.setAccessible(true);
        for(HardwareProfile profile:new HardwareProfile[]{HardwareProfileRegistry.CGB,HardwareProfileRegistry.CGB0})
            for(int[] tail:tails)for(int phase=0;phase<2;phase++)
                try(Gameboy a=GameboyLcdcWritePacketTest.session(profile);Gameboy b=GameboyLcdcWritePacketTest.session(profile)){
                    for(Gameboy g:new Gameboy[]{a,b}){
                        GameboyLcdcWritePacketTest.prepare(g,300);
                        int n=32;while(g.getCpu().getState()!=Cpu.State.OPCODE&&n-->0)g.tick();
                        assertEquals(Cpu.State.OPCODE,g.getCpu().getState());
                        int[] head={0x3e,0x93,0xe0,0x40,0x3e,0xb3,0xe0,0x40};
                        for(int i=0;i<head.length;i++)g.getAddressSpace().setByte(0xff80+i,head[i]);
                        for(int i=0;i<tail.length;i++)g.getAddressSpace().setByte(0xff88+i,tail[i]);
                        g.getCpu().getRegisters().setSP(0xfff0);
                        g.getAddressSpace().setByte(0xfff0,0xe0);g.getAddressSpace().setByte(0xfff1,0xff);
                        g.getCpu().getRegisters().setPC(0xff80);
                        if(phase!=0)g.tick();
                        // Fresh one-shot restore at this exact instruction phase.
                        g.restoreStateSilently(g.captureStateWithoutTimeSource());g.tick();
                        g.getGpu().setPerformanceScanlineEnabled(true);
                    }
                    int elapsed=(int)owner.invoke(b,54L);
                    String label="tail="+java.util.Arrays.toString(tail)+" phase="+phase;
                    assertTrue(label+" must stop before external boundary",elapsed>0&&elapsed<54);
                    assertEquals(label+" both LCDC stores precede fence",2,b.getPerformanceLcdcWriteReplayWrites());
                    for(int t=0;t<elapsed;t++)a.tick();
                    assertStateEquals(label,a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                    for(int dot=0;dot<54;dot++)assertEquals("owner released timeline",0,b.getCpu().replayPerformanceLcdcWritesAtDot(dot));
                    for(int t=0;t<81;t++){a.tick();b.tick();}
                    assertStateEquals(label+" external effect continuation",a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                    b.resetPerformanceBulkCounters();assertEquals(0,b.getPerformanceLcdcWriteReplayTicks());
                    assertEquals(0,b.getPerformanceLcdcWriteReplayWrites());assertEquals(0,b.getPerformanceLcdcWriteReplayQuietTicks());
                }
    }
}
