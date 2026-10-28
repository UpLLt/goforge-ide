package dev.goforge.lsp;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.DocumentMatcher;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.server.definition.extension.FileTypeMappingExtensionPointBean;
import dev.goforge.language.GoFileType;

public final class GoSourceDocumentMatcherTest extends BasePlatformTestCase {
    public void testRegisteredMatcherKeepsPhysicalGoSourceEnabled() throws Exception {
        var path = FileUtil.createTempFile("goforge-source-matcher-", ".go", true);
        java.nio.file.Files.writeString(path.toPath(), "package sample\n");
        var file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path);
        assertNotNull(file);
        assertTrue(matcher().match(file, getProject()));
        assertTrue(LanguageServersRegistry.getInstance().isFileSupported(file, getProject()));
    }

    public void testRegisteredMatcherRejectsLightExpressionDocuments() {
        var file = new LightVirtualFile("DAPExpressionCodeFragment.go", GoFileType.INSTANCE, "message");
        assertFalse(matcher().match(file, getProject()));
    }

    private DocumentMatcher matcher() {
        var mapping = FileTypeMappingExtensionPointBean.EP_NAME.getExtensionList().stream()
                .filter(bean -> "goforge.gopls".equals(bean.serverId) && "GoForge Go".equals(bean.fileType))
                .findFirst().orElseThrow();
        return assertInstanceOf(mapping.getDocumentMatcher(), GoSourceDocumentMatcher.class);
    }
}
