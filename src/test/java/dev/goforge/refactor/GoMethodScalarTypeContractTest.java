package dev.goforge.refactor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Planner phase contracts; actual typed Go safety and compilation need separate protocol probes. */
public final class GoMethodScalarTypeContractTest {
    private static JsonObject response(boolean method) {
        var value = new JsonObject();
        value.addProperty("passed", true);
        value.addProperty("method", method);
        value.addProperty("checked", 0);
        value.add("changes", new JsonObject());
        var target = new JsonObject();
        target.addProperty("uri", Path.of("method.go").toAbsolutePath().toUri().toString());
        var point = new JsonObject(); point.addProperty("line", 2); point.addProperty("character", 16);
        var range = new JsonObject(); range.add("start", point); range.add("end", point.deepCopy());
        target.add("range", range); value.add("target", target);
        return value;
    }

    @Test public void explicitMethodClassificationDistinguishesTheTwoPlanningRoutes() {
        assertTrue(GoChangeScalarType.decodeResult(response(true).toString()).method());
        assertFalse(GoChangeScalarType.decodeResult(response(false).toString()).method());
    }

    @Test public void missingOrCoercedMethodClassificationCannotSelectTheFreeFunctionRoute() {
        var missing = response(true); missing.remove("method");
        assertThrows(IllegalStateException.class, () -> GoChangeScalarType.decodeResult(missing.toString()));
        for (String raw : new String[]{"null", "\"true\"", "\"false\"", "1", "0", "{}", "[]"}) {
            var malformed = response(true); malformed.add("method", JsonParser.parseString(raw));
            assertThrows("Method kind must be an explicit JSON boolean: " + raw,
                    IllegalStateException.class, () -> GoChangeScalarType.decodeResult(malformed.toString()));
        }
    }

    @Test public void declarationAndProposedEditMustKeepTheSameMethodClassification() {
        var method = GoChangeScalarType.decodeResult(response(true).toString());
        var function = GoChangeScalarType.decodeResult(response(false).toString());
        GoChangeScalarType.requireSameKind(method, method);
        GoChangeScalarType.requireSameKind(function, function);
        assertThrows(IllegalStateException.class, () -> GoChangeScalarType.requireSameKind(method, function));
        assertThrows(IllegalStateException.class, () -> GoChangeScalarType.requireSameKind(function, method));
    }

    @Test public void helperRefusalRemainsAFailureForBothMethodKinds() {
        for (boolean method : new boolean[]{false, true}) {
            var refused = response(method); refused.addProperty("passed", false);
            refused.addProperty("reason", "related interface prevents this signature change");
            var failure = assertThrows(IllegalStateException.class,
                    () -> GoChangeScalarType.decodeResult(refused.toString()));
            assertTrue("Keep the helper's refusal available to the user", failure.getMessage().contains("related interface"));
        }
        assertThrows(IllegalStateException.class, () -> GoChangeScalarType.decodeResult("null"));
    }
}
