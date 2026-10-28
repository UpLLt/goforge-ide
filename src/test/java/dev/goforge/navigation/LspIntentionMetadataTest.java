package dev.goforge.navigation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionActionBean;
import com.intellij.codeInsight.intention.impl.config.IntentionActionMetaData;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionAction0;

/** Exercises the dependency copy actually loaded by the platform test sandbox. */
public final class LspIntentionMetadataTest extends BasePlatformTestCase {
    public void testEveryDynamicLspIntentionHasAResolvableStableDescription() throws Exception {
        var beans = ExtensionPointName.<IntentionActionBean>create("com.intellij.intentionAction")
                .getExtensionList().stream().filter(bean -> bean.className != null && bean.className.startsWith(
                        "com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionAction")).toList();
        assertEquals(20, beans.size());
        for (var bean : beans) {
            assertEquals("Empty", bean.getDescriptionDirectoryName());
            var metadata = new IntentionActionMetaData((IntentionAction) bean.getInstance(), bean.getLoaderForClass(),
                    bean.getCategories(), bean.getDescriptionDirectoryName(), false);
            assertTrue(metadata.getDescription().getText().contains("empty kind"));
        }
    }

    public void testLocalizedOrDynamicFamilyDoesNotChooseTheResourceDirectory() throws Exception {
        var action = new LSPIntentionAction0() {
            @Override public String getFamilyName() { return "空"; }
        };
        var metadata = new IntentionActionMetaData(action, LSPIntentionAction0.class.getClassLoader(),
                new String[]{"Language Servers"}, "Empty", false);
        assertTrue(metadata.getDescription().getText().contains("empty kind"));
        assertEquals("空", metadata.getFamily());
    }
}
