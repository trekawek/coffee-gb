package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import org.junit.Test;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.gpu.NativeVblankCoverageSupport.*;
import static org.junit.Assert.*;

public class GameboyNativeVblankRecoveryTest {
    @Test public void repeatedVblankWritesRecoverQuietPixelsWithinTheSamePacket()throws Exception{
        for(HardwareProfile profile:GpuNativeVblankHistoryTest.PROFILES)for(int bit=0;bit<7;bit++){
            try(Gameboy a=rich(profile,0xb3,0xb3^(1<<bit),145,120);Gameboy b=rich(profile,0xb3,0xb3^(1<<bit),145,120)){
                long quiet=b.getPerformanceLcdcWriteReplayQuietTicks(),writes=b.getPerformanceLcdcWriteReplayWrites();
                assertEquals(54,packet(b,54));for(int i=0;i<54;i++)a.tick();
                assertTrue("quiet recovery despite recurring LCDC history bit="+bit,b.getPerformanceLcdcWriteReplayQuietTicks()-quiet>=30);
                assertTrue(b.getPerformanceLcdcWriteReplayWrites()-writes>=3);
                assertStateEquals("positive VBlank quiet owner bit="+bit,a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
            }
        }
    }
}
