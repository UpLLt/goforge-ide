package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class GoRefactorWorkspaceEditTest {
    private static final java.nio.file.Path ROOT=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"goforge-refactor-workspace").toAbsolutePath();
    private static final String A=ROOT.resolve("a.go").toUri().toString(), B=ROOT.resolve("b.go").toUri().toString();
    private static TextEdit edit(String text){return new TextEdit(new Range(new Position(0,0),new Position(0,1)),text);}
    private static List<GoRefactorWorkspaceEdit.Source> sources(){return List.of(new GoRefactorWorkspaceEdit.Source(A,"a",7),new GoRefactorWorkspaceEdit.Source(B,"b",0));}
    @Test public void validatesAllFilesAndKeepsServerEditOrder(){
        var changes=new LinkedHashMap<String,List<TextEdit>>();changes.put(B,List.of(edit("beta")));changes.put(A,List.of(edit("alpha")));
        var plan=GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(changes));
        assertEquals(List.of(B,A),plan.files().stream().map(GoRefactorWorkspaceEdit.FileEdit::uri).toList());
        assertEquals("beta",plan.files().getFirst().edit().replacement());assertEquals("a",sources().getFirst().text());
    }
    @Test public void acceptsPinnedGoplsClosedVersionZeroAndRejectsStaleOverlayVersion(){
        var docs=List.of(Either.<TextDocumentEdit,ResourceOperation>forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(A,7),List.of(edit("aa")))),Either.<TextDocumentEdit,ResourceOperation>forLeft(new TextDocumentEdit(new VersionedTextDocumentIdentifier(B,0),List.of(edit("bb")))));
        assertEquals(2,GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(docs)).files().size());
        docs.getFirst().getLeft().getTextDocument().setVersion(6);
        assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(docs)));
    }
    @Test public void rejectsUncapturedExternalFileAndRemoteUris(){
        assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(Map.of(A,List.of(edit("aa")),"file:///elsewhere/a.go",List.of(edit("bad"))))));
        for(String uri:List.of("https://host/a.go","file://host/a.go","file:///workspace/a.go?x=1","file:///workspace/a.go#x"))assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.uriKey(uri));
    }
    @Test public void acceptsEquivalentLocalUriSpellingButRejectsAliasesInOneBatch(){
        var plan=GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(Map.of(A.replace("file:///","file:/"),List.of(edit("aa")))));
        assertEquals(A,plan.files().getFirst().uri());
        var changes=new LinkedHashMap<String,List<TextEdit>>();changes.put(A,List.of(edit("aa")));changes.put(ROOT.resolve("sub/../a.go").toUri().toString(),List.of(edit("bad")));
        assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(changes)));
    }
    @Test public void rejectsSecondDocumentInvalidRangeBeforeAnyMutation(){
        var invalid=new TextEdit(new Range(new Position(1,0),new Position(1,1)),"bad");
        assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(Map.of(A,List.of(edit("aa")),B,List.of(invalid)))));
        assertEquals("a",sources().getFirst().text());
    }
    @Test public void rejectsDuplicateDocumentsAndFileOperations(){
        var document=new TextDocumentEdit(new VersionedTextDocumentIdentifier(A,7),List.of(edit("aa")));
        assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(List.of(Either.forLeft(document),Either.forLeft(document)))));
        for(ResourceOperation operation:List.of(new CreateFile(A),new RenameFile(A,B),new DeleteFile(B)))
            assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(List.of(Either.forLeft(document),Either.forRight(operation)))));
    }
    @Test public void createsNewFileOnlyAfterExplicitCreateAndValidatesItsZeroVersion(){
        String uri=ROOT.resolve("new.go").toUri().toString();
        var doc=new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri,0),List.of(new TextEdit(new Range(new Position(0,0),new Position(0,0)),"package demo\n")));
        var workspace=new WorkspaceEdit(List.of(Either.forRight(new CreateFile(uri)),Either.forLeft(doc)));
        var result=GoRefactorWorkspaceEdit.prepare(sources(),workspace);
        assertTrue(result.files().getFirst().created());assertEquals("",result.files().getFirst().edit().original());assertEquals("package demo\n",result.files().getFirst().edit().replacement());
        doc.getTextDocument().setVersion(4);assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),workspace));
    }
    @Test public void rejectsCreateOverwriteIgnoreExistingAndEditBeforeCreate(){
        String uri=ROOT.resolve("new.go").toUri().toString();
        for(var options:List.of(new CreateFileOptions(true,false),new CreateFileOptions(false,true))){
            var create=new CreateFile(uri);create.setOptions(options);
            assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(List.of(Either.forRight(create)))));
        }
        var doc=new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri,0),List.of(new TextEdit(new Range(new Position(0,0),new Position(0,0)),"package demo\n")));
        assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources(),new WorkspaceEdit(List.of(Either.forLeft(doc),Either.forRight(new CreateFile(uri))))));
    }

    @Test public void nativePathAliasesPreserveUnicodeAndRejectDuplicateTargets() {
        String uri=ROOT.resolve("space 世界.go").toUri().toASCIIString();
        String alias=ROOT.resolve("sub/../space 世界.go").toUri().toASCIIString();
        var sources=List.of(new GoRefactorWorkspaceEdit.Source(uri,"a",0));
        assertEquals(uri,GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(Map.of(alias,List.of(edit("b"))))).files().getFirst().uri());
        assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(Map.of(uri,List.of(edit("b")),alias,List.of(edit("c"))))));
        if(dev.goforge.tools.HostTools.isWindows()) {
            String lower=uri.replaceFirst("file:/+([A-Z]):","file:///"+Character.toLowerCase(ROOT.toString().charAt(0))+":");
            assertEquals(uri,GoRefactorWorkspaceEdit.prepare(sources,new WorkspaceEdit(Map.of(lower,List.of(edit("b"))))).files().getFirst().uri());
        }
    }
}
