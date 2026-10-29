package dev.goforge.language;

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptionsBase;
import com.intellij.openapi.fileTypes.FileType;
import java.util.Collection;
import java.util.List;

/** A default only: the platform retains explicit project Actions on Save preferences. */
public final class GoFormatOnSaveDefaultsProvider implements FormatOnSaveOptionsBase.DefaultsProvider {
    @Override public Collection<FileType> getFileTypesFormattedOnSaveByDefault() {
        return List.of(GoFileType.INSTANCE);
    }
}
