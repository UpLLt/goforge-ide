package dev.goforge.coverage;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GoCoverageOffsetsTest {
    @Test public void batchConversionKeepsInputOrderAndSharedBoundaries() {
        String source = "a\n世界🙂x\nlast\n";
        var last = new GoCoverageProfile.Block(3, 1, 3, 5, 1, 0);
        var first = new GoCoverageProfile.Block(2, 1, 2, 11, 1, 0);
        var second = new GoCoverageProfile.Block(2, 11, 2, 12, 1, 0);
        var ranges = GoCoverageOffsets.ranges(source, java.util.List.of(last, first, second));
        assertEquals("last", source.substring(ranges.get(0).start(), ranges.get(0).end()));
        assertEquals("世界🙂", source.substring(ranges.get(1).start(), ranges.get(1).end()));
        assertEquals("x", source.substring(ranges.get(2).start(), ranges.get(2).end()));
    }
    @Test public void utf8ByteColumnsBecomeUtf16OffsetsIncludingSurrogatePairs() {
        String source = "package p\n\t世界🙂x()\n";
        var range = GoCoverageOffsets.range(source, new GoCoverageProfile.Block(2, 2, 2, 13, 1, 0));
        assertEquals("世界🙂x", source.substring(range.start(), range.end()));
    }
    @Test public void tabsAndNormalizedCrLfKeepGoColumnMeaning() {
        String source = GoCoverageSourceMap.normalize("a\r\n\tb()\r\n");
        assertEquals(source.indexOf('b'), GoCoverageOffsets.offset(source, 2, 2));
        assertEquals(source.length(), GoCoverageOffsets.offset(source, 3, 1));
    }
    @Test public void invalidOrSplitUnicodePositionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GoCoverageOffsets.offset("世界", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> GoCoverageOffsets.offset("x\n", 3, 1));
        assertThrows(IllegalArgumentException.class, () -> GoCoverageOffsets.offset("x\n", 1, 3));
    }
}
