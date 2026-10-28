package dev.goforge.coverage;

import java.util.*;

/** Go columns count UTF-8 bytes, whereas IntelliJ offsets count UTF-16 code units. */
public final class GoCoverageOffsets {
    private GoCoverageOffsets() { }

    public record Range(int start, int end) { }

    /** Convert a whole file in one forward scan, rather than rescanning it per block on the EDT. */
    public static List<Range> ranges(String source, List<GoCoverageProfile.Block> blocks) {
        var positions = new TreeMap<Long, Integer>();
        for (var block : blocks) {
            positions.put(key(block.startLine(), block.startColumn()), 0);
            positions.put(key(block.endLine(), block.endColumn()), 0);
        }
        int line = 1, column = 1, offset = 0;
        for (var position : positions.entrySet()) {
            int targetLine = (int) (position.getKey() >>> 32), targetColumn = (int) (long) position.getKey();
            while (line < targetLine) {
                int next = source.indexOf('\n', offset);
                if (next < 0) throw new IllegalArgumentException("Coverage line is outside the source snapshot.");
                offset = next + 1; line++; column = 1;
            }
            while (column < targetColumn) {
                if (offset == source.length() || source.charAt(offset) == '\n' || source.charAt(offset) == '\r')
                    throw new IllegalArgumentException("Coverage column is outside the source snapshot.");
                int codePoint = source.codePointAt(offset);
                int width = utf8Width(codePoint);
                if (column + width > targetColumn) throw new IllegalArgumentException("Coverage column splits a UTF-8 character.");
                column += width; offset += Character.charCount(codePoint);
            }
            position.setValue(offset);
        }
        var result = new ArrayList<Range>(blocks.size());
        for (var block : blocks) {
            int start = positions.get(key(block.startLine(), block.startColumn()));
            int end = positions.get(key(block.endLine(), block.endColumn()));
            if (end < start) throw new IllegalArgumentException("Coverage range runs backwards.");
            result.add(new Range(start, end));
        }
        return List.copyOf(result);
    }

    private static long key(int line, int column) {
        if (line < 1 || column < 1) throw new IllegalArgumentException("Invalid coverage position.");
        return ((long) line << 32) | column;
    }

    public static Range range(String source, GoCoverageProfile.Block block) {
        int start = offset(source, block.startLine(), block.startColumn());
        int end = offset(source, block.endLine(), block.endColumn());
        if (end < start) throw new IllegalArgumentException("Coverage range runs backwards.");
        return new Range(start, end);
    }

    public static int offset(String source, int line, int byteColumn) {
        if (line < 1 || byteColumn < 1) throw new IllegalArgumentException("Invalid coverage position.");
        int start = 0;
        for (int current = 1; current < line; current++) {
            int next = source.indexOf('\n', start);
            if (next < 0) throw new IllegalArgumentException("Coverage line is outside the source snapshot.");
            start = next + 1;
        }
        int remaining = byteColumn - 1;
        for (int offset = start;;) {
            if (remaining == 0) return offset;
            if (offset == source.length() || source.charAt(offset) == '\n' || source.charAt(offset) == '\r')
                throw new IllegalArgumentException("Coverage column is outside the source snapshot.");
            int codePoint = source.codePointAt(offset);
            int width = utf8Width(codePoint);
            if (remaining < width) throw new IllegalArgumentException("Coverage column splits a UTF-8 character.");
            remaining -= width;
            offset += Character.charCount(codePoint);
        }
    }
    private static int utf8Width(int codePoint) {
        return codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
    }
}
