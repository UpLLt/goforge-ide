package dev.goforge.language;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.Position;

/** Validate the whole formatting response before the save action changes any bytes. */
final class GoSaveFormattingEdits {
    record Change(int start, int end, String text) { }
    static List<Change> prepare(String text, List<? extends TextEdit> edits) {
        if (edits.size() > 10000) throw new IllegalArgumentException("Formatting exceeds 10,000 edits");
        var starts = new ArrayList<Integer>(); starts.add(0);
        for (int i=0;i<text.length();i++) if (text.charAt(i)=='\n') starts.add(i+1);
        var result = new ArrayList<Change>();
        long length = text.length();
        for (var edit : edits) {
            if (edit == null || edit.getRange() == null || edit.getNewText() == null)
                throw new IllegalArgumentException("Invalid formatting edit");
            int start=offset(text,starts,edit.getRange().getStart()), end=offset(text,starts,edit.getRange().getEnd());
            if (end<start) throw new IllegalArgumentException("Reversed formatting edit");
            length += (long)edit.getNewText().length()-(end-start);
            result.add(new Change(start,end,edit.getNewText()));
        }
        if (length>8*1024*1024) throw new IllegalArgumentException("Formatted document exceeds 8 MiB");
        result.sort(Comparator.comparingInt(Change::start).thenComparingInt(Change::end));
        for (int i=1;i<result.size();i++) {
            var previous=result.get(i-1); var next=result.get(i);
            if (next.start()<previous.end() || next.start()==previous.start())
                throw new IllegalArgumentException("Overlapping formatting edits");
        }
        result.removeIf(edit -> text.substring(edit.start(),edit.end()).equals(edit.text()));
        return List.copyOf(result);
    }
    private static int offset(String text,List<Integer> starts,Position position) {
        if (position==null || position.getLine()<0 || position.getLine()>=starts.size() || position.getCharacter()<0)
            throw new IllegalArgumentException("Formatting position is outside the document");
        int start=starts.get(position.getLine());
        int end=position.getLine()+1<starts.size()?starts.get(position.getLine()+1)-1:text.length();
        if (end>start && text.charAt(end-1)=='\r') end--;
        long result=(long)start+position.getCharacter();
        if (result>end || (result>0 && result<text.length() && Character.isHighSurrogate(text.charAt((int)result-1)) && Character.isLowSurrogate(text.charAt((int)result))))
            throw new IllegalArgumentException("Formatting column splits a character or exceeds its line");
        return (int)result;
    }
}
