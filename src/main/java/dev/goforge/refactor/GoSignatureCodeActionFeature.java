package dev.goforge.refactor;

import com.google.gson.*;
import com.redhat.devtools.lsp4ij.client.features.LSPCodeActionFeature;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.jetbrains.annotations.NotNull;

/** Signature edits use GoForge's checked preview instead of LSP4IJ's direct-apply intention. */
public final class GoSignatureCodeActionFeature extends LSPCodeActionFeature {
    @Override public String getText(@NotNull CodeAction action) {
        if(GoSignatureSafety.required(action.getKind()) || guarded(action.getCommand()) || guardedData(action.getData()))return null;
        return super.getText(action);
    }
    @Override public String getText(@NotNull Command command) {return guarded(command)?null:super.getText(command);}
    static boolean guarded(Command command) {
        if(command==null)return false;
        if("gopls.change_signature".equals(command.getCommand()))return true;
        if(!"gopls.apply_fix".equals(command.getCommand()) || command.getArguments()==null)return false;
        for(var value:command.getArguments()) {
            var element=new Gson().toJsonTree(value);
            if(element.isJsonObject())for(var field:element.getAsJsonObject().entrySet())
                if(field.getKey().equalsIgnoreCase("Fix") && field.getValue().isJsonPrimitive()
                        && "unusedparams".equals(field.getValue().getAsString()))return true;
        }
        return false;
    }
    private static boolean guardedData(Object data) {
        if(data==null)return false;
        try {return guarded(new Gson().fromJson(new Gson().toJsonTree(data),Command.class));}
        catch(JsonParseException | IllegalStateException ignored){return false;}
    }
}
