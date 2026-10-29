package dev.goforge.refactor;

import org.eclipse.lsp4j.*;
import org.junit.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public final class GoChangeSignatureTest {
    private static final String SOURCE="package api\nfunc Format(a string, b int) string { return a }\n";
    private static final String URI=Path.of("signature.go").toAbsolutePath().toUri().toString();
    private static GoChangeSignature prepared(){return GoChangeSignature.prepared(URI,SOURCE,new Position(1,1),new PrepareRenameResult(new Range(new Position(1,0),new Position(1,4)),"func(a string, b int) string"));}
    @Test public void onlyFuncKeywordOffersCustomSignature(){
        assertTrue(GoChangeSignature.candidate(SOURCE,SOURCE.indexOf("func")+2,SOURCE.indexOf("func")+2));
        assertTrue(GoChangeSignature.candidate(SOURCE,SOURCE.indexOf("func"),SOURCE.indexOf("func")+4));
        assertFalse(GoChangeSignature.candidate(SOURCE,SOURCE.indexOf("Format"),SOURCE.indexOf("Format")+3));
        assertFalse(GoChangeSignature.candidate("functional",3,3));
    }
    @Test public void preparedResponseMustReferToSelectedFuncToken(){
        assertNotNull(prepared());
        assertNull(GoChangeSignature.prepared(URI,SOURCE,new Position(1,7),new PrepareRenameResult(new Range(new Position(1,0),new Position(1,4)),"func(a string, b int) string")));
        assertNull(GoChangeSignature.prepared(URI,SOURCE,new Position(1,7),new PrepareRenameResult(new Range(new Position(1,5),new Position(1,11)),"Format")));
    }
    @Test public void signatureRequestKeepsUriAndFuncPosition(){
        var value=prepared().request(" func(b int, a string) string ");assertEquals(URI,value.getTextDocument().getUri());assertEquals(new Position(1,0),value.getPosition());assertEquals("func(b int, a string) string",value.getNewName());
        assertThrows(IllegalArgumentException.class,()->prepared().request("Format"));assertThrows(IllegalArgumentException.class,()->prepared().request(prepared().placeholder()));
    }
    @Test public void genericDeclarationOffersAddParameterWithoutEnablingSignatureRewrite(){
        String source="package api\nfunc Combine[A ~string, B ~string](left A, right B) string {return string(left)+string(right)}\n";
        var response=new PrepareRenameResult(new Range(new Position(1,0),new Position(1,4)),"func[A ~string, B ~string](left A, right B) string");
        assertTrue(GoChangeSignature.preparedDeclaration(source,new Position(1,2),response));
        assertNull(GoChangeSignature.prepared(URI,source,new Position(1,2),response));
        assertFalse(GoChangeSignature.preparedDeclaration(source,new Position(1,7),response));
        assertFalse(GoChangeSignature.preparedDeclaration(source,new Position(1,2),new PrepareRenameResult(response.getRange(),"functional")));
        assertFalse(GoChangeSignature.preparedDeclaration(source,new Position(1,2),new PrepareRenameResult(new Range(new Position(1,5),new Position(1,12)),response.getPlaceholder())));
        assertFalse(GoChangeSignature.preparedDeclaration(source,new Position(1,2),null));
    }
    @Test public void oldAndNewSignatureActionsAllRequireTheSameGuard(){
        for(String kind:List.of("refactor.rewrite.moveParamLeft","refactor.rewrite.moveParamRight","refactor.rewrite.removeUnusedParam","goforge.changeSignature"))assertTrue(GoSignatureSafety.required(kind));
        assertFalse(GoSignatureSafety.required("refactor.extract.toNewFile"));assertFalse(GoSignatureSafety.required(null));
    }
    @Test public void checkerNeutralizesModuleAndCrossCompilationOverrides(){
        var env=GoSignatureSafety.checkerEnvironment(Map.of("GOFLAGS","-toolexec=host-command -tags=secret","GOOS","windows","GOARCH","arm64","GOROOT","/untrusted","GOWORK","/user/go.work","PATH","literal-path"));
        assertEquals("",env.get("GOFLAGS"));assertEquals("",env.get("GOOS"));assertEquals("",env.get("GOARCH"));assertEquals("",env.get("GOROOT"));assertEquals("off",env.get("GOWORK"));assertEquals("off",env.get("GOENV"));assertEquals("off",env.get("GO111MODULE"));assertEquals("off",env.get("GOPROXY"));assertEquals("local",env.get("GOTOOLCHAIN"));assertEquals("0",env.get("CGO_ENABLED"));assertEquals("literal-path",env.get("PATH"));
    }
    @Test public void cancellationDoesNotStartCompilerOrRequestReferences(){
        assertThrows(CancellationException.class,()->GoSignatureSafety.verify("must-not-run",Map.of(),List.of(),URI,new Position(),new GoRefactorWorkspaceEdit(List.of()),target->{throw new AssertionError("No reference request after cancellation");},()->true));
    }
    @Test public void changedPlanSourceIsRejectedBeforeStartingCompiler(){
        var source=new GoRefactorWorkspaceEdit.Source(URI,SOURCE,7);var invalid=new GoRefactorWorkspaceEdit(List.of(new GoRefactorWorkspaceEdit.FileEdit(URI,new GoRefactorEdit("unrelated",SOURCE,List.of()),false)));
        assertThrows(IllegalArgumentException.class,()->GoSignatureSafety.verify("must-not-run",Map.of(),List.of(source),URI,new Position(1,1),invalid,target->{throw new AssertionError();},()->false));
    }
}
