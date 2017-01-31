package eu.rekawek.coffeegb.sound;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LengthTriggerTest extends AbstractLengthCounterTest {

    /*
     set_test 2,"Enabling in second half of length period ","shouldn't clock length"
     call begin
     wchn 1,-2      ; length = 2
     delay_clocks 8256 ; delay until beginning of second half of length period
     wchn 4,$40     ; enable
     ld   a,2
     call end_nodelay
     */
    @Test
    public void test02() {
        begin();
        wchn(1, -2);
        delayClocks(8256);
        wchn(4, 0x40);
        endNoDelay(2);
    }

    /*
     set_test 3,"Enabling in first half of length period should clock length"
     call begin
     wchn 1,-2      ; length = 2
     delay_clocks 7900 ; delay until near-end of first half of length period
     wchn 4,$40     ; enable
     ld   a,1
     call end_nodelay
     */
    @Test
    public void test03() {
        begin();
        wchn(1, -2);
        delayClocks(7900);
        wchn(4, 0x40);
        endNoDelay(1);
    }

    /*
     set_test 4,"Anything besides enabling shouldnt't clock"
     call begin
     wchn 4,$40     ; enable length
     wchn 1,-2      ; length = 2
     wchn 4,$40     ; enabled  -> enabled  doesn't clock
     wchn 4,$00     ; enabled  -> disabled doesn't clock
     wchn 4,$00     ; disabled -> disabled doesn't clock
     ld   a,2
     call end
     */
    @Test
    public void test04() {
        begin();
        wchn(4, 0x40);
        wchn(1, -2);
        wchn(4, 0x40);
        wchn(4, 0x00);
        wchn(4, 0x00);
        end(2);
    }

    /*
     set_test 5,"If clock makes length zero, should disable chan"
     call begin
     wchn 1,-1      ; length = 1
     wchn 4,$40     ; enable, causing clock to zero
     lda  chan_mask
     ld   b,a
     lda  NR52      ; channel now disabled
     and  b
     jp   nz,test_failed
     */
    @Test
    public void test05() {
        begin();
        wchn(1, -1);
        wchn(4, 0x40);
        assertTrue(lengthCounter.isEnabled());
        assertEquals(0, lengthCounter.getValue());
    }

    /*
     set_test 6,"If length already reached zero, shouldn't clock"
     call begin
     wchn 1,-1      ; length = 1
     wchn 4,$40     ; enable, causing clock to zero
     wchn 4,0
     wchn 4,$40     ; no clock; length still 0
     wchn 4,0
     wchn 4,$40     ; no clock; length still 0
     lda  chan_maxlen; end triggers channel, which loads it with max length
     call end
     */
    @Test
    public void test06() {
        begin();
        wchn(1, -1);
        wchn(4, 0x40);
        wchn(4, 0);
        wchn(4, 0x40);
        wchn(4, 0);
        wchn(4, 0x40);
        end(maxlen);
    }

    /*
     set_test 7,"Trigger should un-freeze length that reached zero"
     call begin
     wchn 1,-1      ; length = 1
     wchn 4,$40     ; enable, causing clock to zero
     wchn 4,$00     ; disable
     wchn 4,$80     ; trigger unfreezes length, so it takes on maximum value
     delay_clocks 8192
     wchn 4,$40     ; enable
     delay_apu 2    ; clock length by 2
     lda  chan_maxlen
     sub  2
     call end_nodelay
     */
    @Test
    public void test07() {
        begin();
        wchn(1, -1);
        wchn(4, 0x40);
        wchn(4, 0x00);
        wchn(4, 0x80);
        delayClocks(8192);
        wchn(4, 0x40);
        delayApu(2);
        endNoDelay(maxlen - 2);
    }

    /*
     set_test 8,"Trigger that un-freezes enabled length should clock it"
     call begin
     wchn 1,-1      ; length = 1
     wchn 4,$40     ; enable, causing clock to zero
     wchn 4,$00     ; disable
     wchn 4,$C0     ; trigger unfreezes length, and since enabled, clocks it
     lda  chan_maxlen
     dec  a
     call end_nodelay
     */
    @Test
    public void test08() {
        begin();
        wchn(1, -1);
        wchn(4, 0x40);
        wchn(4, 0x00);
        wchn(4, 0xc0);
        endNoDelay(maxlen - 1);

        begin();
        wchn(1, -1);
        wchn(4, 0x40);
        wchn(4, 0xc0);
        endNoDelay(maxlen - 1);
    }

    /*
     set_test 9,"Triggering that clocks length of 1 ","should clock twice and shouldn't freeze"
     call begin
     wchn 1,-1      ; length = 1
     wchn 4,$C0     ; trigger and enable
                    ; First length counter is enabled, which clocks it to 0 and freezes it
                    ; Trigger unfreezes length counter, which clocks it AGAIN
                    ; The result is the same as the previous test, which enables separately
     lda  chan_maxlen
     dec  a
     call end_nodelay
     */
    @Test
    public void test09() {
        begin();
        wchn(1, -1);
        wchn(4, 0xc0);
        endNoDelay(maxlen - 1);
    }

    /*
     set_test 10,"Trigger shouldn't otherwise affect length"
     call begin
     wchn 1,0       ; length = max
     delay_clocks 8192
     wchn 4,$80     ; trigger
     lda  chan_maxlen
     call end_nodelay
     */
    @Test
    public void test10() {
        begin();
        wchn(1, 0);
        delayClocks(8192);
        wchn(4, 0x80);
        endNoDelay(maxlen);
    }

    /*
     set_test 12,"Other trigger effects should still occur when disabled"
     call sync_apu
     wchn 0,0
     wchn 4,0
     wchn 1,-1
     wchn 4,$40     ; len = 0
     wchn 4,0
     wchn 4,$40     ; len = 0
     wchn 4,$80     ; len = max
     wchn 4,$40     ; len = max-1
     wchn 4,0
     wchn 4,$40     ; len = max-2
     wchn 0,$80     ; enable now
     wchn 4,$C0
     lda  chan_maxlen
     sub  3
     call delay_apu_cycles
     lda  chan_mask
     ld   b,a
     lda  NR52
     and  b
     jp   z,test_failed
     delay_apu 1
     lda  NR52
     and  b
     jp   nz,test_failed
     */
    @Test
    public void test12() {
        syncApu();
        wchn(4, 0);
        wchn(1, -1);
        wchn(4, 0x40);
        wchn(4, 0);
        wchn(4, 0x40);
        wchn(4, 0x80);
        wchn(4, 0x40);
        wchn(4, 0);
        wchn(4, 0x40);
        wchn(4, 0xc0);
        delayApu(maxlen - 3);
        assertTrue(lengthCounter.isEnabled());
        assertNotEquals(0, lengthCounter.getValue());
        delayApu(1);
        assertTrue(lengthCounter.isEnabled());
        assertEquals(0, lengthCounter.getValue());
    }

    /*
     call sync_apu
     wchn 1,-60
     wchn 4,$80
     ret
     */
    private void begin() {
        syncApu();
        wchn(1, -60);
        wchn(4, 0x80);
    }

    /*
    end:
     delay_clocks 8192+1024 ; so we don't clock length when enabling it below
    end_nodelay:
     ld   b,a
     wchn 4,$C0
     ld   a,b
    end_passive:
     ld   b,a
     lda  chan_mask
     call get_len_a
     cp   b
     jp   nz,test_failed
     ret
     */
    private void end(int remainingLength) {
        delayClocks(8192 + 1024);
        endNoDelay(remainingLength);
    }

    private void endNoDelay(int remainingLength) {
        wchn(4, 0xc0);
        endPassive(remainingLength);
    }

    private void endPassive(int remainingLength) {
        assertEquals(remainingLength, lengthCounter.getValue());
    }

}
