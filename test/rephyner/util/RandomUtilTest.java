package rephyner.util;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import beast.base.util.Randomizer;

public class RandomUtilTest {

    @BeforeClass
    public static void setup() {
        // Set a fixed seed for reproducibility
        Randomizer.setSeed(42);
    }

    @Test
    public void testChoice() {
        List<String> list = Arrays.asList("a", "b", "c", "d");
        String result = RandomUtil.choice(list);
        Assert.assertTrue(list.contains(result));
    }

    @Test
    public void testSubsetList() {
        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5);
        List<Integer> subset = RandomUtil.subset(list, 3);
        Assert.assertEquals(3, subset.size());
        Assert.assertTrue(Util.allElementsUnique(subset));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubsetListTooLarge() {
        List<Integer> list = Arrays.asList(1, 2, 3);
        RandomUtil.subset(list, 5);
    }

    @Test
    public void testSubsetBitSet() {
        // BitSet with bits 0-9 and 20-29 set
        BitSet s = new BitSet();
        s.set(0, 10);
        s.set(20, 30);

        BitSet subset = RandomUtil.subset(s, 0.5);
        // Should be a subset of the original
        for (int i = subset.nextSetBit(0); i >= 0; i = subset.nextSetBit(i + 1)) {
            Assert.assertTrue(s.get(i));
        }
        // Should not be empty or full in most cases (fails in two cases out of 2^20)
        Assert.assertTrue(subset.cardinality() > 0 && subset.cardinality() < 20);
    }
}
