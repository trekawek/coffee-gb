package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.*;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.gpu.NativeVblankCoverageSupport.*;
import static org.junit.Assert.*;

public class GameboyNativeVblankLcdcPacketTest {
    @Test public void everyNewLineAndAdmissionRailHasExactStateAndPositiveQueuedWrites()throws Exception{
        for(HardwareProfile profile:GpuNativeVblankHistoryTest.PROFILES)for(int line=143;line<=153;line++)for(int dot:new int[]{12,13,120,432,439,440}){
            try(Gameboy a=rich(profile,0x93,0xb3,line,dot);Gameboy b=rich(profile,0x93,0xb3,line,dot)){
                int expected=line<=152&&dot>=13&&dot<440?Math.min(54,440-dot):0;
                assertEquals("GPU queued packet range",expected,b.getGpu().performanceNativeCgbLcdcWriteReplaySpanLimit(54));
                var before=b.captureStateWithoutTimeSource();long writes=b.getPerformanceLcdcWriteReplayWrites();
                int elapsed=packet(b,54);
                if(expected<8){assertEquals("short/frame rail stays scalar",0,elapsed);assertStateEquals("inert rejected packet",before,b.captureStateWithoutTimeSource());continue;}
                assertEquals(expected,elapsed);for(int t=0;t<elapsed;t++)a.tick();
                assertStateEquals(profile.id()+" line="+line+" dot="+dot,a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                if(elapsed>=23)assertTrue("positive real LCDC writes",b.getPerformanceLcdcWriteReplayWrites()>writes);
                assertEquals(a.getPerformanceNativeFrames(),b.getPerformanceNativeFrames());
                a.restoreStateSilently(a.captureStateWithoutTimeSource());b.restoreStateSilently(b.captureStateWithoutTimeSource());
                for(int t=0;t<500;t++)assertEquals(a.tick(),b.tick());
                assertStateEquals("restored line/frame tail",a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
            }
        }
    }
    @Test public void allLowBitsPendingWritesAndPartialPacketsRemainScalarExact()throws Exception{
        for(HardwareProfile profile:GpuNativeVblankHistoryTest.PROFILES)for(int line:new int[]{143,145,152})for(int bit=0;bit<7;bit++)for(int budget:new int[]{8,23,54}){
            int first=0xb3,second=first^(1<<bit);
            try(Gameboy a=rich(profile,first,second,line,120);Gameboy b=rich(profile,first,second,line,120)){
                // A pending WY copy and CPU LCDC.5 synchronization can coexist at entry.
                for(Gameboy g:new Gameboy[]{a,b}){g.getGpu().setByteFromCpu(0xff4a,3);g.getGpu().setByteFromCpu(0xff40,g.getGpu().getLcdcValueForCore()^0x20);}
                long writes=b.getPerformanceLcdcWriteReplayWrites();int elapsed=packet(b,budget);assertEquals(budget,elapsed);
                for(int t=0;t<elapsed;t++)a.tick();
                String label=profile.id()+" line="+line+" bit="+bit+" budget="+budget;
                assertStateEquals(label,a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
                if(budget==54)assertTrue("multiple actual queued writes",b.getPerformanceLcdcWriteReplayWrites()-writes>=3);
                a.restoreStateSilently(a.captureStateWithoutTimeSource());b.restoreStateSilently(b.captureStateWithoutTimeSource());
                for(int t=0;t<81;t++){a.tick();b.tick();}
                assertStateEquals(label+" restored tail",a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
            }
        }
    }
    @Test public void frameCallbackRemainsAtTheCanonicalCpuGpuStatBoundary()throws Exception{
        for(HardwareProfile profile:GpuNativeVblankHistoryTest.PROFILES)try(Gameboy a=rich(profile,0x93,0xb3,143,432);Gameboy b=rich(profile,0x93,0xb3,143,432)){
            Recorder x=new Recorder(a),y=new Recorder(b);((Display)field(a,"display")).init(x);((Display)field(b,"display")).init(y);
            long frames=b.getPerformanceNativeFrames();assertEquals(8,packet(b,54));for(int i=0;i<8;i++)a.tick();
            assertTrue(x.frames.isEmpty());assertTrue(y.frames.isEmpty());assertEquals(frames,b.getPerformanceNativeFrames());
            for(int i=0;i<32;i++)assertEquals(a.tick(),b.tick());
            assertEquals(1,x.frames.size());assertEquals(1,y.frames.size());
            assertStateEquals("callback order and fully published CPU/GPU/IF/frame",x.frames,y.frames);
            assertStateEquals("after VBlank callback",a.captureStateWithoutTimeSource(),b.captureStateWithoutTimeSource());
        }
    }
    private record Frame(int line,int dot,int pc,Object cpu,Object interrupts,int[]pixels){}
    private static final class Recorder implements EventBus{
        final Gameboy g;final List<Frame>frames=new ArrayList<>();Recorder(Gameboy g){this.g=g;}
        public <E extends Event>void post(E event){if(event instanceof Display.GbcFrameReadyEvent f)try{frames.add(new Frame(g.getGpu().getLine(),g.getGpu().getTicksInLine(),g.getCpu().getRegisters().getPC(),detach(g.getCpu().captureState()),detach(((InterruptManager)field(g,"interruptManager")).captureState()),f.pixels().clone()));}catch(Exception e){throw new AssertionError(e);}}
        public <E extends Event>void postAsync(E e){throw new AssertionError("unexpected asynchronous frame");}
        public <E extends Event>void register(Subscriber<E>s,Class<E>c,String f){}public <E extends Event>void register(Subscriber<E>s,Class<E>c){}public EventBus fork(String s){return this;}public void close(){}
    }
}
