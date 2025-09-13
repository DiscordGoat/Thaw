package goat.thaw.system.monument;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonumentManagerTest {

    @Test
    void parseOffsetsParsesLegacyStrings() throws Exception {
        MonumentManager mm = new MonumentManager(null);
        Method m = MonumentManager.class.getDeclaredMethod("parseOffsets", List.class);
        m.setAccessible(true);
        List<String> raw = List.of("[1,2,3]", "[4,5,6]");
        @SuppressWarnings("unchecked")
        List<int[]> result = (List<int[]>) m.invoke(mm, raw);
        assertEquals(2, result.size());
        assertArrayEquals(new int[]{1,2,3}, result.get(0));
        assertArrayEquals(new int[]{4,5,6}, result.get(1));
    }

    @Test
    void parseOffsetsParsesNestedLists() throws Exception {
        MonumentManager mm = new MonumentManager(null);
        Method m = MonumentManager.class.getDeclaredMethod("parseOffsets", List.class);
        m.setAccessible(true);
        List<List<Integer>> raw = List.of(List.of(7, 8, 9));
        @SuppressWarnings("unchecked")
        List<int[]> result = (List<int[]>) m.invoke(mm, raw);
        assertEquals(1, result.size());
        assertArrayEquals(new int[]{7,8,9}, result.get(0));
    }
}
