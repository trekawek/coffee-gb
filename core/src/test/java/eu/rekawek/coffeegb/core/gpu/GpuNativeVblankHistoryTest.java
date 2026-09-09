package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import org.junit.Test;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.gpu.NativeVblankCoverageSupport.*;
import static org.junit.Assert.*;

public class GpuNativeVblankHistoryTest {
    static final HardwareProfile[]PROFILES={HardwareProfileRegistry.CGB,HardwareProfileRegistry.CGB0};
    @Test public void allLowBitsAndEveryPrefixPreserveRawHistoriesInEveryNativeQuietCommit()throws Exception{
        for(HardwareProfile profile:PROFILES)for(int speed:new int[]{1,2})for(int bit=0;bit<7;bit++){
            try(Gameboy a=quiet(profile,speed,145,80);Gameboy b=quiet(profile,speed,145,80)){
                Gpu x=a.getGpu(),y=b.getGpu();int value=x.getLcdcValueForCore()^(1<<bit);
                x.setByteFromCpu(0xff40,value);y.setByteFromCpu(0xff40,value);
                if(bit==5)assertEquals("pending CPU latch stays scalar",0,y.performanceEpochSpanLimit(54));
                for(int settle=0;settle<(bit==5&&speed==1?2:1);settle++){
                    if(bit==5)assertEquals("pending capture remains a real-dot fence",0,y.performanceEpochSpanLimit(54));
                    x.tick();y.tick();
                }
                assertFalse("fixture has undrained raw LCDC history",((Lcdc)field(y,"lcdc")).isPerformanceQuietSpanFixedPoint());
                if(bit==2)assertFalse("VBlank does not require stable sprite-height history",((Lcdc)field(y,"lcdc")).isPerformanceMode2HeightStable());
                var sx=x.captureState();var sy=y.captureState();
                for(int kind=0;kind<3;kind++)for(int ticks=1;ticks<=54;ticks++){
                    x.restoreState(detach(sx));y.restoreState(detach(sy));
                    assertTrue(profile.id()+" x"+speed+" bit="+bit+" ticks="+ticks+" epoch",y.performanceEpochSpanLimit(ticks)>=ticks);assertTrue("generic quiet",y.performanceQuietSpanLimit()>=ticks);
                    for(int t=0;t<ticks;t++)x.tick();
                    if(kind==0)y.advancePerformanceEpochQuietSpanTrusted(ticks,false,false);
                    else if(kind==1)y.advancePerformanceQuietSpanTrusted(ticks,false,false);
                    else assertTrue(y.advancePerformanceQuietSpan(ticks));
                    String label=profile.id()+" x"+speed+" bit="+bit+" ticks="+ticks+" kind="+kind;
                    assertStateEquals(label,x.captureState(),y.captureState());
                }
            }
        }
    }
    @Test public void readerPrefixAndAllVblankCheckpointsKeepTheirCanonicalEndpoints()throws Exception{
        int[]targets={0,1,2,3,7,8,12,13,75,78,79,80,440,448,449,452,453,454,455};
        for(HardwareProfile profile:PROFILES)for(int speed:new int[]{1,2})for(int line:new int[]{144,145,152,153}){
            try(Gameboy a=quiet(profile,speed,line,0);Gameboy b=quiet(profile,speed,line,0)){
                Gpu x=a.getGpu(),y=b.getGpu();
                for(int target:targets){
                    while(x.getTicksInLine()<target){x.tick();y.tick();}
                    for(int i=0;i<160;i++){int value=(i*17+target)&255;x.setByte(0xfe00+i,value);y.setByte(0xfe00+i,value);}
                    int value=x.getLcdcValueForCore()^4;x.setByte(0xff40,value);y.setByte(0xff40,value);
                    var sx=x.captureState();var sy=y.captureState();
                    int expected=Math.max(0,455-target);if(line==153&&speed==1)expected=Math.min(expected,Math.max(0,454-target));
                    assertEquals("GPU VBlank rail",Math.min(54,expected),y.performanceEpochSpanLimit(54));
                    for(int requested:new int[]{1,7,54}){
                        x.restoreState(detach(sx));y.restoreState(detach(sy));int ticks=Math.min(requested,expected);
                        if(ticks>0){for(int t=0;t<ticks;t++)x.tick();y.advancePerformanceEpochQuietSpanTrusted(ticks,false,false);}
                        assertStateEquals(profile.id()+" x"+speed+" line="+line+" dot="+target+" prefix="+ticks,x.captureState(),y.captureState());
                        x.restoreState(x.captureState());y.restoreState(y.captureState());
                        for(int t=0;t<8;t++)assertEquals(x.tick(),y.tick());
                        assertStateEquals("restored checkpoint tail",x.captureState(),y.captureState());
                    }
                    x.restoreState(detach(sx));y.restoreState(detach(sy));
                }
            }
        }
    }
    @Test public void compatibilityPlaneRetainsItsExistingFixedPointFence()throws Exception{
        for(HardwareProfile profile:PROFILES)try(Gameboy g=quiet(profile,1,145,120)){
            g.getSpeedMode().setDmgCompat(true);g.getGpu().setByte(0xff40,g.getGpu().getLcdcValueForCore()^4);
            assertFalse(((Lcdc)field(g.getGpu(),"lcdc")).isPerformanceQuietSpanFixedPoint());
            assertEquals(0,g.getGpu().performanceEpochSpanLimit(54));assertEquals(0,g.getGpu().performanceQuietSpanLimit());
        }
    }
}
