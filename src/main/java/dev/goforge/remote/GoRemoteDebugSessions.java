package dev.goforge.remote;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import java.util.*;

/** Credentials/arguments/environment stay in project-session memory, not run-configuration XML or process arguments. */
@Service(Service.Level.PROJECT)
public final class GoRemoteDebugSessions implements Disposable {
    private final LinkedHashMap<String,GoRemoteDebugPlan> plans=new LinkedHashMap<>();
    public static GoRemoteDebugSessions getInstance(Project project){return project.getService(GoRemoteDebugSessions.class);}
    public synchronized String register(GoRemoteDebugPlan plan){String id=UUID.randomUUID().toString();plans.put(id,plan);while(plans.size()>16)plans.remove(plans.keySet().iterator().next());return id;}
    public synchronized GoRemoteDebugPlan require(String id){var plan=plans.get(id);if(plan==null)throw new IllegalStateException("This remote debug review expired. Review and start it again from Go Remote > Go debug.");return plan;}
    public synchronized void dispose(){plans.clear();}
}
