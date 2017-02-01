package eu.rekawek.coffeegb.sound;

import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

@Ignore
public class WaveTest {

    private SoundMode3 sm3 = new SoundMode3();

    /*
    main:
     wreg NR51,0    ; mute sound
     loop_n_times test,69
     check_crc_dmg_cgb $118A3620,$270DA9A3
     jp   tests_passed

    test:
     add  $99
     ld   b,a

     ; Reload wave and have its first
     ; sample read occur 2 clocks earlier
     ; each loop iteration
     ld   hl,wave
     call load_wave
     wreg NR30,$80  ; enable
     wreg NR32,$00  ; silent
     ld   a,b
     sta  NR33      ; period
     wreg NR34,$87  ; start

     ; Read from wave
     wreg NR33,-2   ; period = 4
     delay_clocks 176
     lda  WAVE

     call print_a

     ret
     */
    @Test
    public void testReadWhileOn() {
        int[] result = new int[69];
        for (int i = 0; i < result.length; i++) {
            loadWave(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                    0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff);
            wregNR(30, 0x80);
            wregNR(32, 0x00);

            wregNR(33, (i + 0x99));

            delayClocks(20);
            wregNR(34, 0x87);

            delayClocks(20);
            wregNR(33, 0xfe);

            delayClocks(176);
            
            delayClocks(12);
            int w = lda(0xff30);

            result[i] = w;
            System.out.print(String.format("%02X", w));
            if ((i+1) % 6 == 0) {
                System.out.println();
            } else {
                System.out.print(' ');
            }
        }
        assertArrayEquals(new int[]{
                0xff, 0xff, 0x00, 0xff, 0x11, 0xff, 0x11,
                0xff, 0x22, 0xff, 0x22, 0xff, 0x33, 0xff,
                0x33, 0xff, 0x44, 0xff, 0x44, 0xff, 0x55,
                0xff, 0x55, 0xff, 0x66, 0xff, 0x66, 0xff,
                0x77, 0xff, 0x77, 0xff, 0x88, 0xff, 0x88,
                0xff, 0x99, 0xff, 0x99, 0xff, 0xaa, 0xff,
                0xaa, 0xff, 0xbb, 0xff, 0xbb, 0xff, 0xcc,
                0xff, 0xcc, 0xff, 0xdd, 0xff, 0xdd, 0xff,
                0xee, 0xff, 0xee, 0xff, 0xff, 0xff, 0xff,
                0xff, 0x00, 0xff, 0x00, 0xff, 0x11
        }, result);
    }

    private void delayClocks(int clocks) {
        for (int i = 0; i < clocks; i++) {
            sm3.tick();
        }
    }

    private void wregNR(int reg, int value) {
        sm3.setByte(0xff1a + (reg - 30), value);
    }

    private int lda(int address) {
        return sm3.getByte(address);
    }

    private void loadWave(int... wav) {
        for (int i = 0; i < wav.length; i++) {
            sm3.setByte(0xff30 + i, wav[i]);
        }
    }

}
