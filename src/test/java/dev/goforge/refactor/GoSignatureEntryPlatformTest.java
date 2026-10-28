package dev.goforge.refactor;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.*;
import com.redhat.devtools.lsp4ij.features.codeAction.*;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import java.util.*;

/** The actual LSP4IJ lazy intention path must not apply guarded signature edits. */
public final class GoSignatureEntryPlatformTest extends BasePlatformTestCase {
    private LanguageServerItem server;
    @Override protected void setUp()throws Exception {
        super.setUp();myFixture.configureByText(com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE,"unchanged fixture");
        LanguageServerDefinition definition=LanguageServersRegistry.getInstance().getServerDefinition("goforge.gopls");assertNotNull(definition);
        var wrapper=new LanguageServerWrapper(getProject(),definition);Disposer.register(getTestRootDisposable(),wrapper);server=new LanguageServerItem(null,wrapper);
        assertInstanceOf(wrapper.getClientFeatures().getCodeActionFeature(),GoSignatureCodeActionFeature.class);
    }
    private LSPLazyCodeActionIntentionAction intention(Either<Command,CodeAction> action){
        var result=new LSPLazyCodeActionIntentionAction(0);result.setLazyCodeActions(index->Either.forLeft(new CodeActionData(action,server)));return result;
    }
    public void testSignatureActionsCannotEnterDefaultLazyIntentionApply(){
        for(String kind:List.of("refactor.rewrite.moveParamLeft","refactor.rewrite.moveParamRight","refactor.rewrite.removeUnusedParam")){
            var code=new CodeAction("signature");code.setKind(kind);code.setEdit(new WorkspaceEdit(Map.of("file:///must-not-apply.go",List.of(new TextEdit(new Range(new Position(),new Position()),"unsafe")))));
            var action=intention(Either.forRight(code));assertFalse(action.isAvailable(getProject(),myFixture.getEditor(),myFixture.getFile()));action.invoke(getProject(),myFixture.getEditor(),myFixture.getFile());assertEquals("unchanged fixture",myFixture.getEditor().getDocument().getText());
        }
        for(var command:List.of(new Command("signature","gopls.change_signature",List.of()),new Command("unused parameter","gopls.apply_fix",List.of(Map.of("Fix","unusedparams"))))) {
            var action=intention(Either.forLeft(command));assertFalse(action.isAvailable(getProject(),myFixture.getEditor(),myFixture.getFile()));action.invoke(getProject(),myFixture.getEditor(),myFixture.getFile());
            var fix=new CodeAction("Remove unused parameter");fix.setKind("quickfix");fix.setCommand(command);assertFalse(intention(Either.forRight(fix)).isAvailable(getProject(),myFixture.getEditor(),myFixture.getFile()));
            var lazy=new CodeAction("Resolve parameter fix");lazy.setKind("quickfix");lazy.setData(Map.of("command",command.getCommand(),"arguments",command.getArguments()));assertFalse(intention(Either.forRight(lazy)).isAvailable(getProject(),myFixture.getEditor(),myFixture.getFile()));
        }
        assertEquals("unchanged fixture",myFixture.getEditor().getDocument().getText());
    }
    public void testRenameToBlankOtherQuickfixAndExtractRemainAvailable(){
        for(String kind:List.of("quickfix","refactor.extract.function","source.organizeImports")){
            var code=new CodeAction(kind.equals("quickfix")?"Rename parameter to _":"Ordinary edit");code.setKind(kind);code.setEdit(new WorkspaceEdit(Map.of()));var action=intention(Either.forRight(code));
            assertTrue(action.isAvailable(getProject(),myFixture.getEditor(),myFixture.getFile()));assertEquals(code.getTitle(),action.getText());
        }
        var command=new Command("Fill return values","gopls.apply_fix",List.of(Map.of("Fix","fillreturns")));assertTrue(intention(Either.forLeft(command)).isAvailable(getProject(),myFixture.getEditor(),myFixture.getFile()));
        assertInstanceOf(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("GoForge.Refactor"),GoRefactorAction.class);
    }
}
