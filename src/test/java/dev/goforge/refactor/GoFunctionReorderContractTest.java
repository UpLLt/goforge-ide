package dev.goforge.refactor;
import org.junit.Test;
import static org.junit.Assert.*;
public final class GoFunctionReorderContractTest {
 @Test public void exactTypedReferenceCoverageIsRequired(){GoFunctionReorderSafety.requireVerified("{\"passed\":true,\"checked\":2}",2);for(String data:new String[]{"{\"passed\":true}","{\"passed\":true,\"checked\":1}","{\"passed\":false,\"checked\":2}","null"})assertThrows(IllegalStateException.class,()->GoFunctionReorderSafety.requireVerified(data,2));}
 @Test public void typedRefusalIsRetained(){var failure=assertThrows(IllegalStateException.class,()->GoFunctionReorderSafety.requireVerified("{\"passed\":false,\"reason\":\"caller shadows scalar\",\"checked\":0}",1));assertEquals("caller shadows scalar",failure.getMessage());}
 @Test public void originalSignaturePathKeepsItsExistingSafetyGate(){assertTrue(GoSignatureSafety.required("goforge.changeSignature"));assertTrue(GoSignatureSafety.required("refactor.rewrite.moveParamLeft"));assertFalse(GoSignatureSafety.required("goforge.reorderScalarCalls"));}
}
