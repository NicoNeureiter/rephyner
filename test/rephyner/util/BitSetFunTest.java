package rephyner.util;

import org.junit.Test;
import java.util.BitSet;
import static org.junit.Assert.*;


public class BitSetFunTest {
    @Test
    public void testToBitString_AllZeros() {
        BitSet bs = new BitSet(5);
        String s = BitSetFun.toBitString(bs, 5);
        assertEquals("00000", s);
    }

    @Test
    public void testToBitString_Mixed() {
        BitSet bs = new BitSet(5);
        bs.set(1);
        bs.set(3);
        String s = BitSetFun.toBitString(bs, 5);
        assertEquals("01010", s);
    }

    @Test
    public void testToBitString_EmptyBitSet() {
        BitSet bs = new BitSet(0);
        String s = BitSetFun.toBitString(bs, 0);
        assertEquals("", s);
    }

    @Test
    public void testToBitString_LargerNBitsThanSet() {
        BitSet bs = new BitSet(3);
        bs.set(0);
        String s = BitSetFun.toBitString(bs, 5);
        assertEquals("10000", s);
    }

}
