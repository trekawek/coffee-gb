package eu.rekawek.coffeegb.sound;

import org.junit.Test;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LengthCounterTest extends AbstractLengthCounterTest {

    /*
     set_test 2,"Length becoming 0 should clear status"
     call begin
     delay_apu 3
     call should_be_almost_off
     */
    @Test
    public void test02() {
        begin();
        delayApu(3);
        shouldBeAlmostOff();
    }

    /*
     set_test 3,"Length can be reloaded at any time"
     call begin
     wchn 1,-10     ; length = 10
     delay_apu 9
     call should_be_almost_off
     */
    @Test
    public void test03() {
        begin();
        wchn(1, -10);
        delayApu(9);
        shouldBeAlmostOff();
    }

    /*
     set_test 4,"Attempting to load length with 0 should load with maximum"
     call begin
     wchn 1,0       ; length = maximum
     lda  chan_maxlen
     dec  a
     call delay_apu_cycles
     call should_be_almost_off
     */
    @Test
    public void test04() {
        begin();
        wchn(1, 0);
        delayApu(maxlen - 1);
        shouldBeAlmostOff();
    }

    /*
     set_test 5,"Trigger shouldn't affect length"
     call begin
     delay_apu 1
     wchn 4,$C0     ; length unaffected
     delay_apu 2
     call should_be_almost_off
     */
    @Test
    public void test05() {
        begin();
        delayApu(1);
        wchn(4, 0xc0);
        delayApu(2);
        shouldBeAlmostOff();
    }

    /*
     set_test 6,"Trigger should treat 0 length as maximum"
     call begin
     delay_apu 4    ; clocks length to 0
     wchn 4,$C0     ; trigger converts 0 to maximum
     lda  chan_maxlen
     dec  a
     call delay_apu_cycles
     call should_be_almost_off
     */
    @Test
    public void test06() {
        begin();
        delayApu(4);
        wchn(4, 0xc0);
        delayApu(maxlen - 1);
        shouldBeAlmostOff();
    }

    /*
     set_test 7,"Trigger with disabled length should convert ","0 length to maximum"
     call begin
     delay_apu 4    ; clocks length to 0
     wchn 4,$00     ; disable length
     wchn 4,$80     ; trigger converts 0 to maximum
     wchn 4,$40     ; enable length
     lda  chan_maxlen
     dec  a
     call delay_apu_cycles
     call should_be_almost_off
     */
    @Test
    public void test07() {
        begin();
        delayApu(4);
        wchn(4, 0x00);
        wchn(4, 0x80);
        wchn(4, 0x40);
        delayApu(maxlen - 1);
        shouldBeAlmostOff();
    }

    /*
     set_test 8,"Disabling length shouldn't re-enable channel"
     call begin
     delay_apu 4    ; clocks length to 0
     call should_be_off
     wchn 4,0       ; disable length
     call should_be_off
     */
    @Test
    public void test08() {
        begin();
        delayApu(4);
        shouldBeOff();
        wchn(4, 0);
        // following depends on the channel enabled flag (not part of the length counter)
//        shouldBeOff();
    }

    /*
     set_test 9,"Disabling length should stop length clocking"
     call begin
     wchn 4,0       ; disable length
     delay_apu 4    ; length isn't affected
     wchn 4,$40     ; enable length
     delay_apu 3    ; clocks length to 1
     call should_be_almost_off
     */
    @Test
    public void test09() {
        begin();
        wchn(4, 0);
        delayApu(4);
        wchn(4, 0x40);
        delayApu(3);
        shouldBeAlmostOff();
    }

    /*
     set_test 10,"Reloading shouldn't re-enable channel"
     call begin
     delay_apu 4    ; clocks length to 0
     call should_be_off
     wchn 1,-2      ; length = 2
     call should_be_off
     */
    @Test
    public void test10() {
        begin();
        delayApu(4);
        shouldBeOff();
        wchn(1, -2);
        // following depends on the channel enabled flag (not part of the length counter)
        //shouldBeOff();
    }

    /*
     set_test 11,"Disabled channel should still clock length"
     call begin
     delay_apu 4    ; clocks length to 0, disabling channel
     wchn 1,-8      ; length = 8
     delay_apu 4    ; clocks length to 4
     wchn 4,$C0     ; trigger, enabling channel
     delay_apu 3    ; clocks length to 1
     call should_be_almost_off
     */
    @Test
    public void test11() {
        begin();
        delayApu(4);
        wchn(1, -8);
        delayApu(4);
        wchn(4, 0xc0);
        delayApu(3);
        shouldBeAlmostOff();
    }

    /*
     set_test 12,"Disabled channel should still convert 0 load to max length"
     call begin
     delay_apu 4    ; clocks length to 0, disabling channel
     wchn 1,0       ; length = maximum
     delay_apu 32   ; clock length 32 times
     wchn 4,$C0
     lda  chan_maxlen
     sub  33
     call delay_apu_cycles
     call should_be_almost_off
     */
    @Test
    public void test12() {
        begin();
        delayApu(4);
        wchn(1, 0);
        delayApu(32);
        wchn(4, 0xc0);
        delayApu(maxlen - 33);
        shouldBeAlmostOff();
    }

    /*
    begin:
     call sync_apu
     delay 2048     ; avoid extra clocking due to quirks
     wchn 4,$40     ; avoid extra clocking due to quirks
     wchn 1,-4      ; length = 4
     wchn 4,$C0     ; trigger, enabling channel
     ret
     */
    private void begin() {
        syncApu();
        delay(2048);
        wchn(4, 0x40);
        wchn(1, -4);
        wchn(4, 0xc0);
    }

    /*
    should_be_on:
     lda  chan_mask
     ld   b,a
     lda  NR52
     and  b
     jp   z,test_failed
     ret
     */
    private void shouldBeOn() {
        if (lengthCounter.isEnabled()) {
            assertNotEquals(0, lengthCounter.getValue());
        }
    }

    /*
    should_be_almost_off:
     call should_be_on
     delay_apu 1
    should_be_off:
     lda  chan_mask
     ld   b,a
     lda  NR52
     and  b
     jp   nz,test_failed
     ret
     */
    private void shouldBeAlmostOff() {
        shouldBeOn();
        delayApu(1);
        shouldBeOff();
    }

    private void shouldBeOff() {
        assertTrue(lengthCounter.isEnabled() && lengthCounter.getValue() == 0);
    }

    private void delay(int cpuCycles) {
        delayClocks(cpuCycles * 4);
    }
}
