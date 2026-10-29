package dev.goforge.refactor;

import com.google.gson.JsonObject;
import org.eclipse.lsp4j.*;
import org.junit.Test;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Pure phase-boundary tests; the actual Go classifier and compiler have separate opt-in probes. */
public final class GoMethodParameterContractTest {
    private static String response(boolean method,boolean needsCompilation) {
        var object=new JsonObject();object.addProperty("passed",true);object.addProperty("method",method);
        object.addProperty("needsCompilation",needsCompilation);object.addProperty("reason","compile required");
        var target=new JsonObject();target.addProperty("uri",Path.of("method.go").toAbsolutePath().toUri().toString());
        var range=new JsonObject();var point=new JsonObject();point.addProperty("line",2);point.addProperty("character",16);
        range.add("start",point);range.add("end",point.deepCopy());target.add("range",range);object.add("target",target);
        return object.toString();
    }
    @Test public void privateMethodCannotBypassMandatoryProofWhenCompilationHintIsAbsent() {
        assertThrows(GoAddParameter.CompilationRequired.class,()->GoAddParameter.decodeResult(response(true,false),false));
        assertTrue(GoAddParameter.decodeResult(response(true,false),true).method());
    }
    @Test public void helperCompilationRequirementStillAppliesToBothPlanningPaths() {
        assertThrows(GoAddParameter.CompilationRequired.class,()->GoAddParameter.decodeResult(response(true,true),false));
        assertThrows(GoAddParameter.CompilationRequired.class,()->GoAddParameter.decodeResult(response(true,true),true));
        assertFalse(GoAddParameter.decodeResult(response(false,false),false).method());
    }
    @Test public void typedProofMustCoverEveryReferenceAndCannotIgnoreRefusal() {
        GoMethodParameterSafety.requireVerified("{\"passed\":true,\"checked\":2}",2);
        assertThrows(IllegalStateException.class,()->GoMethodParameterSafety.requireVerified("{\"passed\":true,\"checked\":1}",2));
        assertThrows(IllegalStateException.class,()->GoMethodParameterSafety.requireVerified("{\"passed\":false,\"checked\":2,\"reason\":\"related interface\"}",2));
        assertThrows(IllegalStateException.class,()->GoMethodParameterSafety.requireVerified("null",2));
        assertThrows(IllegalStateException.class,()->GoMethodParameterSafety.requireVerified("{\"passed\":true}",0));
        assertThrows(IllegalStateException.class,()->GoMethodParameterSafety.requireVerified("{\"passed\":true,\"checked\":10001}",10001));
    }
    @Test public void methodFuncEntryUsesExistingVerifiedDeclarationRouteOnly() {
        String text="package p\ntype worker struct{}\nfunc(w worker) combine(a int){}\n";
        int at=text.indexOf("func");var point=GoRefactorEdit.position(text,at);
        var prepared=new PrepareRenameResult(new Range(point,GoRefactorEdit.position(text,at+4)),"func(a int)");
        assertTrue(GoChangeSignature.candidate(text,at,at));
        assertTrue(GoChangeSignature.preparedDeclaration(text,point,prepared));
        assertFalse(GoChangeSignature.preparedDeclaration(text,GoRefactorEdit.position(text,text.indexOf("combine")),prepared));
    }
}
