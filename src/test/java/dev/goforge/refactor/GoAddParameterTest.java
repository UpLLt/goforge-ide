package dev.goforge.refactor;

import com.google.gson.Gson;
import org.eclipse.lsp4j.*;
import org.junit.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public final class GoAddParameterTest {
    private static final String URI=Path.of("add-parameter.go").toAbsolutePath().toUri().toString();
    private static final GoAddParameter.Spec SPEC=new GoAddParameter.Spec("extra","int",0,"7");
    @Test public void generatedDocumentEditRoundTripsThroughLoadedProtocolModel() {
        String original="package p\nfunc F(){}\n";
        var point=GoRefactorEdit.position(original,original.indexOf(')'));
        var document=GoAddParameter.documentEdit(new VersionedTextDocumentIdentifier(URI,7),
                List.of(new TextEdit(new Range(point,point),"extra int")));
        var gson=new Gson();
        var wire=gson.toJsonTree(document);
        assertEquals("extra int",wire.getAsJsonObject().getAsJsonArray("edits").get(0).getAsJsonObject().get("newText").getAsString());
        var decoded=gson.fromJson(wire,TextDocumentEdit.class);
        var workspace=new WorkspaceEdit(List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(decoded)));
        var plan=GoRefactorWorkspaceEdit.prepare(List.of(new GoRefactorWorkspaceEdit.Source(URI,original,7)),workspace);
        assertEquals("package p\nfunc F(extra int){}\n",plan.files().getFirst().edit().replacement());
        assertThrows(IllegalArgumentException.class,()->GoRefactorWorkspaceEdit.prepare(
                List.of(new GoRefactorWorkspaceEdit.Source(URI,original,8)),workspace));
    }
    @Test public void requestTrimsInputWithoutEvaluatingDefault() {
        var request=new GoAddParameter.Spec(" extra "," int ",1," -7 ");
        assertEquals("extra",request.name());assertEquals("int",request.type());assertEquals(1,request.index());assertEquals("-7",request.defaultValue());
        assertThrows(IllegalArgumentException.class,()->new GoAddParameter.Spec("extra","int",-1,"7"));
        assertThrows(IllegalArgumentException.class,()->new GoAddParameter.Spec("extra","int",0," "));
        assertThrows(IllegalArgumentException.class,()->new GoAddParameter.Spec("extra","int",0,"1".repeat(4097)));
    }
    @Test public void cancellationBeforePlanningCannotStartCompilerOrAskForReferences() {
        assertThrows(CancellationException.class,()->GoAddParameter.plan("must-not-start",Map.of(),List.of(),URI,new Position(),SPEC,
                target->{throw new AssertionError("Unexpected reference request");},()->true));
    }
    @Test public void uncapturedDeclarationRefusesBeforeCompiler() {
        var source=new GoRefactorWorkspaceEdit.Source(URI,"package p\nfunc F(){}\n",1);
        assertThrows(IllegalArgumentException.class,()->GoAddParameter.plan("must-not-start",Map.of(),List.of(source),
                Path.of("outside.go").toAbsolutePath().toUri().toString(),new Position(),SPEC,target->{throw new AssertionError();},()->false));
    }
    @Test public void duplicateSnapshotAliasesRefuseBeforeCompiler() {
        var first=new GoRefactorWorkspaceEdit.Source(URI,"package p\nfunc F(){}\n",1);
        var second=new GoRefactorWorkspaceEdit.Source(URI,"package p\nfunc Other(){}\n",2);
        assertThrows(IllegalArgumentException.class,()->GoAddParameter.plan("must-not-start",Map.of(),List.of(first,second),URI,new Position(),SPEC,target->{throw new AssertionError();},()->false));
    }
}
