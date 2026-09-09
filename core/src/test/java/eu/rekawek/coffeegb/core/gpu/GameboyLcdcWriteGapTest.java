package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;

/** Compare admitted gap commits with canonical scalar execution on identical state. */
public class GameboyLcdcWriteGapTest {
    @Test public void quietWriteGapsMatchScalarAcrossRestoredPrefixes() throws Exception {
        var gap=Gameboy.class.getDeclaredMethod("tryPerformanceLcdcWriteReplayEpoch",long.class);
        gap.setAccessible(true);
        boolean recovered=false;
        for(HardwareProfile profile:new HardwareProfile[]{HardwareProfileRegistry.CGB,HardwareProfileRegistry.CGB0})
            for(int target:new int[]{20,68,120,240,300,420})for(int phase=0;phase<2;phase++)
                for(int budget:new int[]{8,9,23,53,54})
                    try(Gameboy a=GameboyLcdcWritePacketTest.session(profile);Gameboy b=GameboyLcdcWritePacketTest.session(profile)){
                        GameboyLcdcWritePacketTest.prepare(a,target+phase);GameboyLcdcWritePacketTest.prepare(b,target+phase);
                        a.restoreStateSilently(a.captureStateWithoutTimeSource());
                        b.restoreStateSilently(b.captureStateWithoutTimeSource());
                        a.tick();b.tick();
                        a.getGpu().setPerformanceScanlineEnabled(true);b.getGpu().setPerformanceScanlineEnabled(true);
                        int actual=(int)gap.invoke(b,(long)budget);
                        assertTrue("admitted packet",actual>0);
                        for(int t=0;t<actual;t++)a.tick();
                        assertStateEquals("gap dot="+target+" phase="+phase+" budget="+budget,
                                a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                        recovered|=b.getPerformanceLcdcWriteReplayQuietTicks()>0;
                        for(int t=0;t<81;t++){a.tick();b.tick();}
                        assertStateEquals("canonical continuation",a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                    }
        assertTrue("must actually commit a quiet prefix between writes",recovered);
    }
}
