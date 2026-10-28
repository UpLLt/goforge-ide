package dev.goforge.refactor;

import org.eclipse.lsp4j.*;

/** The pinned gopls prepareRename-on-func contract; ordinary symbol rename is not intercepted. */
public record GoChangeSignature(String uri,Range keyword,String placeholder) {
    /** In-memory markers, with no additional protocol fields, for the checked preview path. */
    public static final class PrepareRequest extends PrepareRenameParams {
        public PrepareRequest(TextDocumentIdentifier document,Position position){super(document,position);}
    }
    public static final class RenameRequest extends RenameParams {
        private RenameRequest(TextDocumentIdentifier document,Position position,String value){super(document,position,value);}
    }
    public static boolean candidate(String source,int start,int end) {
        if(start<0 || end<start || end>source.length())return false;
        int left=start,right=start;
        while(left>0 && Character.isJavaIdentifierPart(source.codePointBefore(left)))left-=Character.charCount(source.codePointBefore(left));
        while(right<source.length() && Character.isJavaIdentifierPart(source.codePointAt(right)))right+=Character.charCount(source.codePointAt(right));
        return source.substring(left,right).equals("func") && end<=right;
    }
    public static GoChangeSignature prepared(String uri,String source,Position selection,PrepareRenameResult prepared) {
        if(prepared==null || prepared.getRange()==null || prepared.getPlaceholder()==null)return null;
        int start=GoRefactorEdit.offset(source,prepared.getRange().getStart()),end=GoRefactorEdit.offset(source,prepared.getRange().getEnd());
        int at=GoRefactorEdit.offset(source,selection);
        if(!source.substring(start,end).equals("func") || at<start || at>end || !prepared.getPlaceholder().startsWith("func("))return null;
        return new GoChangeSignature(uri,prepared.getRange(),prepared.getPlaceholder());
    }
    public RenameParams request(String value) {
        if(value==null || value.length()>16384 || !value.strip().startsWith("func("))
            throw new IllegalArgumentException("Enter a function type beginning with func(. Keep existing parameter names/types and result types.");
        if(value.strip().equals(placeholder))throw new IllegalArgumentException("Change the parameter order or remove an unused parameter before previewing.");
        return new RenameRequest(new TextDocumentIdentifier(uri),keyword.getStart(),value.strip());
    }
}
