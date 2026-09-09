package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.*;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.hardware.*;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

/** The optimized DMA clock stays interleaved with a real object-bearing mode-3 reader. */
public class GameboyDmaWramReplayDotTest {
    @Test public void detailedPpuObservesEveryRestoredDotAndFinalRelease() throws Exception {
        var owner=Gameboy.class.getDeclaredMethod("tryPerformanceNativeCgbDetailedPpuEpoch",long.class);owner.setAccessible(true);
        boolean[] phases={false,false};boolean full=false;
        for(HardwareProfile profile:new HardwareProfile[]{HardwareProfileRegistry.CGB,HardwareProfileRegistry.CGB0})
            for(boolean odd:new boolean[]{false,true})for(int requested=1;requested<=54;requested++)
                try(Gameboy scalar=session(profile);Gameboy fast=session(profile)){
                    prepare(scalar,odd);prepare(fast,odd);
                    scalar.restoreStateSilently(scalar.captureStateWithoutTimeSource());
                    fast.restoreStateSilently(fast.captureStateWithoutTimeSource());
                    scalar.tick();fast.tick(); // settle the derived STAT cache while keeping live DMA
                    scalar.getGpu().setPerformanceScanlineEnabled(true);fast.getGpu().setPerformanceScanlineEnabled(true);
                    assertEquals(Mode.PixelTransfer,fast.getGpu().getMode());
                    assertFalse(fast.getGpu().isPerformanceSteadyCursorActive());
                    assertEquals("must retain per-dot PPU rather than the quiet copy lane",0,
                            fast.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(54));
                    Dma d=dma(fast);var before=(Dma.DmaState)d.captureState();
                    phases[before.transferClocks()&1]=true;
                    assertEquals(odd?1:0,before.transferClocks()&1);
                    int elapsed=(int)owner.invoke(fast,(long)requested);
                    if(requested<8)assertEquals("short owner budget remains scalar",0,elapsed);
                    else assertTrue("detailed packet must use the replay dot implementation",elapsed>0);
                    full|=elapsed==54;
                    for(int n=0;n<elapsed;n++)scalar.tick();
                    assertEquals(before.transferClocks()+2*elapsed,((Dma.DmaState)d.captureState()).transferClocks());
                    assertStateEquals("profile="+profile.id()+" odd="+odd+" requested="+requested,
                            scalar.captureStateWithoutTimeSource(),fast.captureStateWithoutTimeSource());
                    scalar.restoreStateSilently(scalar.captureStateWithoutTimeSource());
                    fast.restoreStateSilently(fast.captureStateWithoutTimeSource());
                    for(int n=0;n<350;n++){scalar.tick();fast.tick();}
                    assertFalse(d.isTransferInProgress());assertFalse(d.ownsOamForPpu());
                    assertStateEquals("restored scalar GPU/DMA release",scalar.captureStateWithoutTimeSource(),fast.captureStateWithoutTimeSource());
                }
        assertTrue("both transfer-clock parities",phases[0]&&phases[1]);
        assertTrue("at least one complete54-dot detailed replay",full);
    }

    private static void prepare(Gameboy g,boolean odd)throws Exception{
        g.setPerformanceBatchingEnabled(false);var bus=g.getAddressSpace();var gpu=g.getGpu();
        bus.setByte(0xff26,0);bus.setByte(0xff41,0);bus.setByte(0xff45,255);bus.setByte(0xff0f,0);
        gpu.setByte(0xff40,0);
        for(int n=0;n<160;n++){
            bus.setByte(0xc000+n,(n*37+0x53)&255);
            gpu.setByte(0xfe00+n,n%4==0?16:n%4==1?16+(n/4%10)*12:n%4==2?n/4+1:0);
        }
        for(int address=0x8000;address<0x9800;address++)gpu.setByte(address,(address*37^address>>>4)&255);
        gpu.setByte(0xff40,0x93);gpu.setPerformanceScanlineEnabled(false);
        int[] code={0x04,0x0c,0x18,0xfc};for(int n=0;n<code.length;n++)bus.setByte(0xff80+n,code[n]);
        g.getCpu().getRegisters().setPC(0xff80);toggle(g);
        for(int limit=140448;limit>0;limit--){
            if(gpu.getLine()==2&&gpu.getTicksInLine()==100)break;
            g.tick();if(limit==1)fail("phase fixture");
        }
        if(odd)toggle(g);bus.setByte(0xff46,0xc0);
        if(odd){g.tick();toggle(g);}
        for(int n=0;n<5;n++)g.tick();
        // Mod-4 phase may have occupied its other half during the one normal-speed dot.
        for(int limit=8;!g.getCpu().performanceEpochEntryEligible()&&limit>0;limit--)g.tick();
        assertTrue(g.getCpu().performanceEpochEntryEligible());
        assertEquals(54,dma(g).performanceNativeCgbWramReplaySpanLimit(54));
    }
    private static void toggle(Gameboy g)throws Exception{
        g.getSpeedMode().setByte(0xff4d,1);var m=SpeedMode.class.getDeclaredMethod("onStop");m.setAccessible(true);
        assertEquals(true,m.invoke(g.getSpeedMode()));
    }
    private static Dma dma(Gameboy g)throws Exception{
        var f=Gameboy.class.getDeclaredField("dma");f.setAccessible(true);return (Dma)f.get(g);
    }
    private static Gameboy session(HardwareProfile profile)throws Exception{
        byte[] image=new byte[0x8000];image[0x100]=(byte)0xc3;image[0x101]=0;image[0x102]=1;image[0x143]=(byte)0x80;
        return new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(()->0L).setSupportBatterySave(false).build();
    }
}
