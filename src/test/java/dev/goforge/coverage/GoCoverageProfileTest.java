package dev.goforge.coverage;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class GoCoverageProfileTest {
    @Test public void statementWeightsAndDuplicateBlocksAreNotDoubleCounted() throws Exception {
        var profile = GoCoverageProfile.parse("mode: count\nb/f.go:5.1,5.4 9 0\na/f.go:2.1,3.2 1 2\na/f.go:2.1,3.2 1 3\n");
        assertEquals("a/f.go", profile.files().getFirst().name());
        assertEquals(5, profile.files().getFirst().blocks().getFirst().count());
        assertEquals(1, profile.totals().covered());
        assertEquals(10, profile.totals().statements());
        assertEquals("10.0%", profile.totals().display());
    }
    @Test public void setAndAtomicProfilesMergeTheirRespectiveCounters() throws Exception {
        for (String mode : new String[]{"set", "atomic"}) {
            var profile = GoCoverageProfile.parse("mode: " + mode + "\nx.go:1.1,2.1 2 1\nx.go:1.1,2.1 2 1\n");
            assertEquals(mode.equals("set") ? 1 : 2, profile.files().getFirst().blocks().getFirst().count());
            assertEquals(2, profile.totals().statements());
        }
    }
    @Test public void filenamesMayContainSpacesAndDriveColons() throws Exception {
        var profile = GoCoverageProfile.parse("mode: set\nC:/source with spaces/f.go:1.1,2.1 1 0\n");
        assertEquals("C:/source with spaces/f.go", profile.files().getFirst().name());
    }
    @Test public void zeroStatementsAreNotOneHundredPercent() throws Exception {
        assertEquals("—", GoCoverageProfile.parse("mode: atomic\n").totals().display());
        assertEquals("—", GoCoverageProfile.parse("mode: set\nx.go:1.1,1.2 0 0\n").totals().display());
    }
    @Test public void malformedEmptyAndUnknownModeProfilesFailExplicitly() {
        for (String text : new String[]{"", "mode: strange\n", "mode: set\n\n", "mode: set\nx.go:0.1,2.1 1 0\n",
                "mode: set\nx.go:2.1,1.1 1 0\n", "mode: set\nx.go:1.1,2.1 -1 0\n",
                "mode: set\nx.go:1.1,2.1 1\n", "mode: set\nx.go:1.1,2.1 1 9223372036854775808\n"}) {
            assertThrows(IOException.class, () -> GoCoverageProfile.parse(text));
        }
    }
    @Test public void inconsistentDuplicateStatementsAndOverflowFail() {
        assertThrows(IOException.class, () -> GoCoverageProfile.parse("mode: count\nx.go:1.1,2.1 1 1\nx.go:1.1,2.1 2 1\n"));
        assertThrows(IOException.class, () -> GoCoverageProfile.parse("mode: atomic\nx.go:1.1,2.1 1 9223372036854775807\nx.go:1.1,2.1 1 1\n"));
        assertThrows(IOException.class, () -> GoCoverageProfile.parse("mode: set\nx.go:1.1,2.1 9223372036854775807 0\ny.go:1.1,2.1 1 0\n"));
    }
}
