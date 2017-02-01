package eu.rekawek.coffeegb.sound;

import org.junit.Test;

import static eu.rekawek.coffeegb.Gameboy.TICKS_PER_SEC;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SweepTest {

    private final FrequencySweep sweep = new FrequencySweep();

    /*
     set_test 2,"If shift>0, calculates on trigger"
     call begin
     wreg NR10,$01
     wreg NR13,$FF
     wreg NR14,$C7
     call should_be_off
     call begin
     wreg NR10,$11
     wreg NR13,$FF
     wreg NR14,$C7
     call should_be_off
     */
    @Test
    public void test04_2() {
        begin();
        wregNR(10, 0x01);
        wregNR(13, 0xff);
        wregNR(14, 0xc7);
        shouldBeOff();

        begin();
        wregNR(10, 0x11);
        wregNR(13, 0xff);
        wregNR(14, 0xc7);
        shouldBeOff();
    }

    /*
     set_test 3,"If shift=0, doesn't calculate on trigger"
     call begin
     wreg NR10,$10
     wreg NR13,$FF
     wreg NR14,$C7
     delay_apu 1
     call should_be_almost_off
     */
    @Test
    public void test04_3() {
        begin();
        wregNR(10, 0x10);
        wregNR(13, 0xff);
        wregNR(14, 0xc7);
        delayApu(1);
        shouldBeAlmostOff();
    }

    /*
     set_test 4,"If period=0, doesn't calculate"
     call begin
     wreg NR10,$00
     wreg NR13,$FF
     wreg NR14,$C7
     delay_apu $20
     call should_be_almost_off
     */
    @Test
    public void test04_4() {
        begin();
        wregNR(10, 0x00);
        wregNR(13, 0xff);
        wregNR(14, 0xc7);
        delayApu(0x20);
        shouldBeOn();
    }

    /*
     set_test 5,"After updating frequency, calculates a second time"
     call begin
     wreg NR10,$11
     wreg NR13,$00
     wreg NR14,$C5
     delay_apu 1
     call should_be_almost_off
     */
    @Test
    public void test04_5() {
        begin();
        wregNR(10, 0x11);
        wregNR(13, 0x00);
        wregNR(14, 0xc5);
        delayApu(1);
        shouldBeAlmostOff();
    }

    /*
     set_test 6,"If calculation>$7FF, disables channel"
     call begin
     wreg NR10,$02
     wreg NR13,$67
     wreg NR14,$C6
     call should_be_off
     */
    @Test
    public void test04_6() {
        begin();
        wregNR(10, 0x02);
        wregNR(13, 0x67);
        wregNR(14, 0xc6);
        shouldBeOff();
    }

    /*
     set_test 7,"If calculation<=$7FF, doesn't disable channel"
     call begin
     wreg NR10,$01
     wreg NR13,$55
     wreg NR14,$C5
     delay_apu $20
     call should_be_almost_off
     */
    @Test
    public void test04_7() {
        begin();
        wregNR(10, 0x01);
        wregNR(13, 0x55);
        wregNR(14, 0xc5);
        shouldBeOn();
    }

    /*
     set_test 8,"If shift=0 and period>0, trigger enables"
     call begin
     wreg NR10,$10
     wreg NR13,$FF
     wreg NR14,$C3
     delay_apu 2
     wreg NR10,$11
     delay_apu 1
     call should_be_almost_off
     */
    @Test
    public void test04_8() {
        begin();
        wregNR(10, 0x10);
        wregNR(13, 0xff);
        wregNR(14, 0xc3);
        delayApu(2);
        wregNR(10, 0x11);
        delayApu(1);
        shouldBeAlmostOff();
    }

    /*
     set_test 9,"If shift>0 and period=0, trigger enables"
     call begin
     wreg NR10,$01
     wreg NR13,$FF
     wreg NR14,$C3
     delay_apu 15
     wreg NR10,$11
     call should_be_almost_off
     */
    @Test
    public void test04_9() {
        begin();
        wregNR(10, 0x01);
        wregNR(13, 0xff);
        wregNR(14, 0xc3);
        delayApu(15);
        wregNR(10, 0x11);
        shouldBeAlmostOff();
    }

    /*
     set_test 10,"If shift=0 and period=0, trigger disables"
     call begin
     wreg NR10,$08
     wreg NR13,$FF
     wreg NR14,$C3
     wreg NR10,$11
     delay_apu $20
     call should_be_almost_off
     */
    @Test
    public void test04_10() {
        begin();
        wregNR(10, 0x08);
        wregNR(13, 0xff);
        wregNR(14, 0xc3);
        wregNR(10, 0x11);
        delayApu(0x20);
        shouldBeOn();
    }

    /*
     set_test 11,"If shift=0, doesn't update"
     call begin
     wreg NR10,$10
     wreg NR13,$FF
     wreg NR14,$C3
     delay_apu $20
     call should_be_almost_off
     */
    @Test
    public void test04_11() {
        begin();
        wregNR(10, 0x10);
        wregNR(13, 0xff);
        wregNR(14, 0xc3);
        delayApu(0x20);
        shouldBeOn();
    }

    /*
     set_test 12,"If period=0, doesn't update"
     call begin
     wreg NR10,$01
     wreg NR13,$00
     wreg NR14,$C5
     delay_apu $20
     call should_be_almost_off
     */
    @Test
    public void test04_12() {
        begin();
        wregNR(10, 0x01);
        wregNR(13, 0x00);
        wregNR(14, 0xc5);
        delayApu(0x20);
        shouldBeOn();
    }

    /*
     set_test 2,"Timer treats period 0 as 8"
     call begin
     wreg NR10,$11
     wreg NR13,$00
     wreg NR14,$C2
     delay_apu 1
     wreg NR10,$01  ; sweep enabled
     delay_apu 3
     wreg NR10,$11  ; non-zero period so calc will occur when timer reloads
     delay_apu $11
     call should_be_almost_off
     */
    @Test
    public void test05_02() {
        begin();
        wregNR(10, 0x11);
        wregNR(13, 0x00);
        wregNR(14, 0xc2);
        delayApu(1);
        wregNR(10, 0x01);
        delayApu(3);
        wregNR(10, 0x11);
        delayApu(0x11);
        shouldBeOn();
    }

    /*
    begin:
     call sync_sweep
     wreg NR14,$40
     wreg NR11,-$21
     wreg NR12,$08
     ret
     */
    private void begin() {
        syncSweep();
        wregNR(14, 0x40);
    }

    private void shouldBeOn() {
        assertTrue(sweep.isEnabled());
    }

    /*
    should_be_almost_off:
     lda  NR52
     and  $01
     jp   z,test_failed
     delay_apu 1
    should_be_off:
     lda  NR52
     and  $01
     jp   nz,test_failed
     ret
     */
    private void shouldBeAlmostOff() {
        assertTrue(sweep.isEnabled());
        delayApu(1);
        shouldBeOff();
    }

    private void shouldBeOff() {
        assertFalse(sweep.isEnabled());
    }

    /*
    sync_sweep:
     wreg NR10,$11  ; sweep period = 1, shift = 1
     wreg NR12,$08  ; silent without disabling channel
     wreg NR13,$FF  ; freq = $3FF
     wreg NR14,$83  ; trigger
-    lda  NR52
     and  $01
     jr   nz,-
     ret
     */
    private void syncSweep() {
        wregNR(10, 0x11);
        wregNR(13, 0xff);
        wregNR(14, 0x83);
        while (sweep.isEnabled()) {
            sweep.tick();
        }
    }

    private void wregNR(int reg, int value) {
        switch (reg) {
            case 10:
                sweep.setNr10(value);
                break;

            case 13:
                sweep.setNr13(value);
                break;

            case 14:
                sweep.setNr14(value);
                break;

            default:
                throw new IllegalArgumentException();
        }
    }

    private void delayApu(int apuCycles) {
        for (int i = 0; i < TICKS_PER_SEC / 256 * apuCycles; i++) {
            sweep.tick();
        }
    }
}
