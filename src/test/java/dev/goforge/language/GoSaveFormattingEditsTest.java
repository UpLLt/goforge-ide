package dev.goforge.language;

import org.junit.Test;
import org.eclipse.lsp4j.*;
import java.util.List;
import static org.junit.Assert.*;

public final class GoSaveFormattingEditsTest {
    private static TextEdit edit(int line,int start,int end,String value) {
        return new TextEdit(new Range(new Position(line,start),new Position(line,end)),value);
    }
    @Test public void preservesValidUtf16AndCrLfPositions() {
        var changes=GoSaveFormattingEdits.prepare("// 😀\r\nvar x=1",List.of(edit(1,5,6," = "),edit(0,3,5,"🌍")));
        assertEquals(List.of(new GoSaveFormattingEdits.Change(3,5,"🌍"),new GoSaveFormattingEdits.Change(12,13," = ")),changes);
    }
    @Test public void rejectsSplitSurrogateBeforeAnyEditCanBeApplied() {
        assertThrows(IllegalArgumentException.class,()->GoSaveFormattingEdits.prepare("// 😀",List.of(edit(0,0,0,"good"),edit(0,4,5,"bad"))));
    }
    @Test public void rejectsOutsideColumnRatherThanClampingIntoNextLine() {
        assertThrows(IllegalArgumentException.class,()->GoSaveFormattingEdits.prepare("a\nb",List.of(edit(0,1,2,"bad"))));
    }
    @Test public void rejectsOverlapsAndAmbiguousSamePositionInserts() {
        assertThrows(IllegalArgumentException.class,()->GoSaveFormattingEdits.prepare("abc",List.of(edit(0,0,2,"x"),edit(0,1,3,"y"))));
        assertThrows(IllegalArgumentException.class,()->GoSaveFormattingEdits.prepare("abc",List.of(edit(0,1,1,"x"),edit(0,1,1,"y"))));
    }
    @Test public void dropsIdenticalEditsWithoutChangingModificationStamp() {
        assertTrue(GoSaveFormattingEdits.prepare("abc",List.of(edit(0,0,3,"abc"))).isEmpty());
    }
}
