package dev.goforge.navigation;

import com.google.gson.JsonParser;
import dev.goforge.lsp.GoplsServerFactory;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public final class GoplsLauncherTest {
    @Test public void factoryLauncherUsesDefaultClassloaderAndAdaptsActualRpcError() {
        var output = new ByteArrayOutputStream();
        var launcher = new GoplsServerFactory().createClientFeatures().<LanguageServer>createLauncherBuilder()
                .setInput(new ByteArrayInputStream(new byte[0])).setOutput(output)
                .setLocalService(new Object()).setRemoteInterface(LanguageServer.class).create();
        var result = launcher.getRemoteProxy().getTextDocumentService().implementation(
                new ImplementationParams(new TextDocumentIdentifier("file:///probe.go"), new Position(0, 0)));
        String frame = output.toString(StandardCharsets.UTF_8);
        var request = JsonParser.parseString(frame.substring(frame.indexOf("\r\n\r\n") + 4)).getAsJsonObject();
        assertEquals("textDocument/implementation", request.get("method").getAsString());
        var response = new ResponseMessage();
        response.setId(request.get("id").getAsString());
        response.setError(new ResponseError(0,
                "Hello is a function, not a method (query at 'func' token to find matching signatures)", null));
        launcher.getRemoteEndpoint().consume(response);
        assertEquals(List.of(), result.join().getLeft());
    }
}
