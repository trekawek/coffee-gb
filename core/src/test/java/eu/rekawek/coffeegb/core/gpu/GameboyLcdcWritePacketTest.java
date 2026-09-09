package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.*;
import eu.rekawek.coffeegb.core.hardware.*;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

public class GameboyLcdcWritePacketTest {
    @Test public void multipleLcdcWritesMatchEveryRestoredPrefixAndModeHandoff() throws Exception {
        boolean fullMultiWritePacket = false;
        for(HardwareProfile profile:new HardwareProfile[]{HardwareProfileRegistry.CGB,HardwareProfileRegistry.CGB0})
            for(int target:new int[]{20,68,120,240,300,420})for(int phase=0;phase<2;phase++)
                for(int budget:new int[]{8,9,23,53,54})try(Gameboy a=session(profile);Gameboy b=session(profile)){
                    prepare(a,target+phase);prepare(b,target+phase);
                    var sa=a.captureStateWithoutTimeSource();var sb=b.captureStateWithoutTimeSource();
                        a.restoreStateSilently(sa);b.restoreStateSilently(sb);
                        a.tick();b.tick(); // settle derived STAT restore cache, retaining identical hardware phase
                        a.getGpu().setPerformanceScanlineEnabled(true);b.getGpu().setPerformanceScanlineEnabled(true);
                        var before=b.captureStateWithoutTimeSource();
                        long writes=b.getPerformanceLcdcWriteReplayWrites();
                        var owner=Gameboy.class.getDeclaredMethod("tryPerformanceLcdcWriteReplayEpoch",long.class);owner.setAccessible(true);
                        int elapsed=(int)owner.invoke(b,(long)budget);
                        assertTrue("positive packet at mode/phase "+target+"/"+phase,elapsed>0);
                        for(int t=0;t<elapsed;t++)a.tick();
                        assertStateEquals(profile.id()+" dot="+target+" phase="+phase+" budget="+budget,
                                a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                        if(budget==54&&elapsed==54) { fullMultiWritePacket=true; assertTrue("multiple actual write dots: target="+target+" phase="+phase+" elapsed="+elapsed+" writes="+(b.getPerformanceLcdcWriteReplayWrites()-writes),
                                b.getPerformanceLcdcWriteReplayWrites()-writes>=3); }
                        for(int t=0;t<81;t++){a.tick();b.tick();}
                        assertStateEquals("write/readback continuation",a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                        for(int address:new int[]{0xff40,0xff41,0xff44,0xff0f})
                            assertEquals(a.getAddressSpace().getByte(address),b.getAddressSpace().getByte(address));
                }
        assertTrue("at least one full54-dot multi-write packet must be exercised",fullMultiWritePacket);
    }
    @Test public void statSourceAndRetainedPhasedRequestsRejectWithoutMutation() throws Exception {
        try(Gameboy g=session(HardwareProfileRegistry.CGB)){
            prepare(g,300);var owner=Gameboy.class.getDeclaredMethod("tryPerformanceLcdcWriteReplayEpoch",long.class);owner.setAccessible(true);
            g.getAddressSpace().setByte(0xff41,0x20);
            for(int t=0;t<8;t++)g.tick();
            var before=g.captureStateWithoutTimeSource();assertEquals(0,owner.invoke(g,54L));
            assertStateEquals("enabled source rejection",before,g.captureStateWithoutTimeSource());
            g.getAddressSpace().setByte(0xff41,0);for(int t=0;t<8;t++)g.tick();
            var irqField=Gameboy.class.getDeclaredField("interruptManager");irqField.setAccessible(true);
            InterruptManager irq=(InterruptManager)irqField.get(g);
            irq.requestMode2InterruptBeforeCpuAcceptance(false);
            assertFalse("masked/blocked phase is hidden by the ordinary request getter",irq.isPhasedMode2InterruptRequested());
            before=g.captureStateWithoutTimeSource();assertEquals(0,owner.invoke(g,54L));
            assertStateEquals("retained raw phase rejection",before,g.captureStateWithoutTimeSource());
        }
    }
    @Test public void pendingLcdcAndRetainedNormalClockScxLatchesRemainExact() throws Exception {
        for(HardwareProfile profile:new HardwareProfile[]{HardwareProfileRegistry.CGB,HardwareProfileRegistry.CGB0})
            for(int target:new int[]{20,120,300})for(boolean scx:new boolean[]{false,true})
                for(int budget:new int[]{8,23,54})try(Gameboy a=session(profile);Gameboy b=session(profile)){
                    prepare(a,target);prepare(b,target);
                    for(Gameboy g:new Gameboy[]{a,b}){
                        if(scx){
                            // The old-value SCX latch is created only at native x1. Carry its
                            // real pending state across the fixture's immediate clock switch.
                            toggleSpeed(g);g.getGpu().setByteFromCpu(0xff43,5);toggleSpeed(g);
                            g.tick(); // settle STAT while the SCX old-value latch becomes visible
                            var field=Gpu.class.getDeclaredField("r");field.setAccessible(true);
                            assertTrue(((GpuRegisterValues)field.get(g.getGpu())).hasPendingConflictLatches());
                        }else{
                            g.getGpu().setByteFromCpu(0xff40,g.getGpu().getByte(0xff40)^0x20);
                            var field=Gpu.class.getDeclaredField("pendingPpuWrites");field.setAccessible(true);
                            assertFalse(((java.util.List<?>)field.get(g.getGpu())).isEmpty());
                        }
                    }
                    var owner=Gameboy.class.getDeclaredMethod("tryPerformanceLcdcWriteReplayEpoch",long.class);owner.setAccessible(true);
                    int elapsed=(int)owner.invoke(b,(long)budget);assertTrue("pending latch must admit the exact replay",elapsed>0);
                    for(int t=0;t<elapsed;t++)a.tick();
                    assertStateEquals("pending="+scx+" dot="+target+" budget="+budget,
                            a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                    for(int t=0;t<81;t++){a.tick();b.tick();}
                    assertStateEquals("pending-latch continuation",a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                }
    }
    static void toggleSpeed(Gameboy g)throws Exception{
        g.getSpeedMode().setByte(0xff4d,1);var m=g.getSpeedMode().getClass().getDeclaredMethod("onStop");m.setAccessible(true);assertEquals(true,m.invoke(g.getSpeedMode()));
    }
    static void prepare(Gameboy g,int dot)throws Exception{
        g.setPerformanceBatchingEnabled(false);g.getAddressSpace().setByte(0xff26,0);
        g.getAddressSpace().setByte(0xff41,0);g.getAddressSpace().setByte(0xff45,255);g.getAddressSpace().setByte(0xff0f,0);
        g.getSpeedMode().setByte(0xff4d,1);var m=g.getSpeedMode().getClass().getDeclaredMethod("onStop");m.setAccessible(true);assertEquals(true,m.invoke(g.getSpeedMode()));
        g.getGpu().setPerformanceScanlineEnabled(true);
        for(int budget=140448;budget>0;budget--){if(g.getGpu().getLine()==2&&g.getGpu().getTicksInLine()==dot)return;g.tick();}
        fail("fixture phase");
    }
    static Gameboy session(HardwareProfile p)throws Exception{
        byte[] image=new byte[0x8000];image[0x100]=(byte)0xc3;image[0x101]=0x50;image[0x102]=1;image[0x143]=(byte)0x80;
        int[] loop={0x3e,0x93,0xe0,0x40,0x3e,0xb3,0xe0,0x40,0xc3,0x50,1};for(int i=0;i<loop.length;i++)image[0x150+i]=(byte)loop[i];
        return new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(p).setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE).setRtcTimeSource(()->0L).setSupportBatterySave(false).build();
    }
}
