package dev.goforge.run;

/** Reads only the package declaration, after leading whitespace and comments. Not a Go parser. */
public final class GoSourcePackage {
    private GoSourcePackage() {}
    public static String name(String source) {
        int offset = skipTrivia(source, source.startsWith("\uFEFF") ? 1 : 0);
        if (!source.startsWith("package", offset)) return null;
        offset += "package".length();
        if (offset < source.length() && isIdentifierPart(source.codePointAt(offset))) return null;
        offset = skipTrivia(source, offset);
        if (offset >= source.length()) return null;
        int first = source.codePointAt(offset);
        if (first != '_' && !Character.isLetter(first)) return null;
        int end = offset + Character.charCount(first);
        while (end < source.length() && isIdentifierPart(source.codePointAt(end)))
            end += Character.charCount(source.codePointAt(end));
        return source.substring(offset, end);
    }
    private static boolean isIdentifierPart(int character) { return character == '_' || Character.isLetterOrDigit(character); }
    private static int skipTrivia(String source, int offset) {
        while (offset < source.length()) {
            if (Character.isWhitespace(source.charAt(offset))) { offset++; continue; }
            if (source.startsWith("//", offset)) {
                int end = source.indexOf('\n', offset + 2);
                if (end < 0) return source.length();
                offset = end + 1; continue;
            }
            if (source.startsWith("/*", offset)) {
                int end = source.indexOf("*/", offset + 2);
                if (end < 0) return source.length();
                offset = end + 2; continue;
            }
            break;
        }
        return offset;
    }
}
