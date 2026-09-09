package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

final class NativeVblankCoverageSupport {
    static Gameboy quiet(HardwareProfile profile, int speed, int line, int dot) throws Exception {
        byte[] image=new byte[0x8000];image[0x100]=(byte)0xc3;image[0x101]=0x50;image[0x102]=1;image[0x143]=(byte)0x80;
        image[0x150]=0x18;image[0x151]=(byte)0xfe;
        Gameboy g=new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(()->0L).setSupportBatterySave(false).build();
        g.setPerformanceBatchingEnabled(false);g.getGpu().setPerformanceScanlineEnabled(false);
        g.getAddressSpace().setByte(0xff26,0);g.getAddressSpace().setByte(0xff41,0);
        g.getAddressSpace().setByte(0xff45,255);g.getAddressSpace().setByte(0xff0f,0);
        if(speed==2)GameboyLcdcWritePacketTest.toggleSpeed(g);
        for(int remaining=160000;remaining>0;remaining--){
            if(g.getGpu().getLine()==line&&g.getGpu().getTicksInLine()==dot){g.getGpu().setPerformanceScanlineEnabled(true);return g;}
            g.tick();
        }
        g.close();throw new AssertionError("quiet fixture");
    }
    static Gameboy rich(HardwareProfile profile,int first,int second,int line,int dot)throws Exception{
        Method s=GameboyLcdcWritePacketPpuProofTest.class.getDeclaredMethod("session",HardwareProfile.class,int.class,int.class);s.setAccessible(true);
        Gameboy g=(Gameboy)s.invoke(null,profile,first,second);
        Method p=GameboyLcdcWritePacketPpuProofTest.class.getDeclaredMethod("prepare",Gameboy.class,int.class,int.class);p.setAccessible(true);p.invoke(null,g,line,dot);return g;
    }
    static int packet(Gameboy g,int ticks)throws Exception{Method m=Gameboy.class.getDeclaredMethod("tryPerformanceLcdcWriteReplayEpoch",long.class);m.setAccessible(true);return (int)m.invoke(g,(long)ticks);}
    static Object field(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    @SuppressWarnings("unchecked") static <T>T detach(T value)throws Exception{
        if(value==null)return null;Class<?>c=value.getClass();
        if(c.isArray()){int n=Array.getLength(value);Object copy=Array.newInstance(c.getComponentType(),n);if(c.getComponentType().isPrimitive())System.arraycopy(value,0,copy,0,n);else for(int i=0;i<n;i++)Array.set(copy,i,detach(Array.get(value,i)));return(T)copy;}
        if(value instanceof List<?>l){List<Object>copy=new ArrayList<>();for(Object e:l)copy.add(detach(e));return(T)copy;}
        if(c.isRecord()){var fs=c.getRecordComponents();Class<?>[]types=new Class<?>[fs.length];Object[]args=new Object[fs.length];for(int i=0;i<fs.length;i++){types[i]=fs[i].getType();var a=fs[i].getAccessor();a.setAccessible(true);args[i]=detach(a.invoke(value));}var ctor=c.getDeclaredConstructor(types);ctor.setAccessible(true);return(T)ctor.newInstance(args);}
        return value;
    }
}
