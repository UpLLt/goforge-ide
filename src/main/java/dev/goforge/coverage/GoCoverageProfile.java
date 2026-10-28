package dev.goforge.coverage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Pattern;

/** Reads the public go test coverprofile format; percentages are statement-weighted. */
public record GoCoverageProfile(String mode, List<FileCoverage> files) {
    private static final Pattern LINE = Pattern.compile("(.+):([0-9]+)\\.([0-9]+),([0-9]+)\\.([0-9]+) ([0-9]+) ([0-9]+)");
    public GoCoverageProfile { files = List.copyOf(files); }

    public record Block(int startLine, int startColumn, int endLine, int endColumn, long statements, long count) {
        public boolean covered() { return count > 0; }
    }
    public record Totals(long covered, long statements) {
        public String display() {
            return statements == 0 ? "—" : String.format(Locale.ROOT, "%.1f%%", 100.0 * covered / statements);
        }
    }
    public record FileCoverage(String name, List<Block> blocks) {
        public FileCoverage { blocks = List.copyOf(blocks); }
        public Totals totals() {
            long total = 0, covered = 0;
            for (Block block : blocks) {
                total = Math.addExact(total, block.statements());
                if (block.covered()) covered = Math.addExact(covered, block.statements());
            }
            return new Totals(covered, total);
        }
    }

    public Totals totals() {
        long total = 0, covered = 0;
        for (FileCoverage file : files) {
            Totals value = file.totals();
            total = Math.addExact(total, value.statements());
            covered = Math.addExact(covered, value.covered());
        }
        return new Totals(covered, total);
    }

    public static GoCoverageProfile parse(String text) throws IOException { return parse(new StringReader(text)); }

    public static GoCoverageProfile parse(Reader reader) throws IOException {
        var input = new BufferedReader(reader);
        String header = input.readLine();
        if (header == null || !Set.of("mode: set", "mode: count", "mode: atomic").contains(header))
            throw new IOException("Missing or unsupported coverage mode; expected set, count or atomic.");
        String mode = header.substring(6);
        var files = new TreeMap<String, LinkedHashMap<List<Integer>, Block>>();
        int lineNumber = 1;
        for (String line; (line = input.readLine()) != null;) {
            lineNumber++;
            if (lineNumber > 1_000_001) throw new IOException("Coverage profile exceeds one million blocks.");
            var match = LINE.matcher(line);
            if (!match.matches() || match.group(1).indexOf('\0') >= 0)
                throw new IOException("Malformed coverage record at line " + lineNumber + ".");
            try {
                int sl = Integer.parseInt(match.group(2)), sc = Integer.parseInt(match.group(3));
                int el = Integer.parseInt(match.group(4)), ec = Integer.parseInt(match.group(5));
                long statements = Long.parseLong(match.group(6)), count = Long.parseLong(match.group(7));
                if (sl < 1 || sc < 1 || el < sl || ec < 1 || (sl == el && ec < sc))
                    throw new IllegalArgumentException("Invalid source range");
                var key = List.of(sl, sc, el, ec);
                var blocks = files.computeIfAbsent(match.group(1), ignored -> new LinkedHashMap<>());
                Block previous = blocks.get(key);
                if (previous != null) {
                    if (previous.statements() != statements) throw new IllegalArgumentException("Inconsistent statement count for duplicate block");
                    count = mode.equals("set") ? previous.count() | count : Math.addExact(previous.count(), count);
                }
                blocks.put(key, new Block(sl, sc, el, ec, statements, count));
            } catch (IllegalArgumentException | ArithmeticException error) {
                throw new IOException("Invalid coverage record at line " + lineNumber + ": " + error.getMessage(), error);
            }
        }
        var result = new ArrayList<FileCoverage>();
        var order = Comparator.comparingInt(Block::startLine).thenComparingInt(Block::startColumn)
                .thenComparingInt(Block::endLine).thenComparingInt(Block::endColumn);
        files.forEach((name, blocks) -> result.add(new FileCoverage(name, blocks.values().stream().sorted(order).toList())));
        var profile = new GoCoverageProfile(mode, result);
        try { profile.totals(); }
        catch (ArithmeticException error) { throw new IOException("Coverage statement totals overflow.", error); }
        return profile;
    }
}
