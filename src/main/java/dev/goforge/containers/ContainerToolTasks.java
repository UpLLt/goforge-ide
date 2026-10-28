package dev.goforge.containers;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** One cancellable operation per panel. Managed results transfer ownership only at EDT publication. */
final class ContainerToolTasks implements Disposable {
    @FunctionalInterface interface Job<T> { T run(BooleanSupplier cancelled, Consumer<String> progress) throws Exception; }
    private static final class Run {
        volatile boolean cancelled, workerCompleted;
        Object result; Throwable failure;
        final AtomicBoolean finished = new AtomicBoolean();
        final Supplier<String> abandon;
        Run(Supplier<String> abandon) { this.abandon = abandon; }
    }
    private final Project project;
    private final AtomicReference<Run> active = new AtomicReference<>();
    private volatile boolean disposed;
    ContainerToolTasks(Project project) { this.project=project; com.intellij.openapi.util.Disposer.register(project,this); }
    boolean busy() { return active.get()!=null; }
    boolean workerCompleted() { var run=active.get(); return run!=null && run.workerCompleted; }
    void cancel() { var run=active.get(); if(run!=null) run.cancelled=true; }
    <T> void submit(String title,Job<T> job,Consumer<T> result,Consumer<String> failure,Consumer<Boolean> busy) {
        submitManaged(title,job,result,failure,busy,null);
    }
    <T> void submitManaged(String title,Job<T> job,Consumer<T> result,Consumer<String> failure,
                           Consumer<Boolean> busy,Supplier<String> abandon) {
        var run=new Run(abandon);
        if(disposed||project.isDisposed()||!active.compareAndSet(null,run)) return;
        busy.accept(true);
        new Task.Backgroundable(project,title,true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try { run.result=job.run(()->run.cancelled||indicator.isCanceled()||disposed||project.isDisposed(),indicator::setText); }
                catch(Throwable e) { run.failure=e; }
                finally {
                    if(indicator.isCanceled()) run.cancelled=true;
                    run.workerCompleted=true;
                    if(disposed||project.isDisposed()) abandon(run,failure,busy);
                }
            }
            @Override public void onCancel() { run.cancelled=true; }
            @Override public void onFinished() {
                run.workerCompleted=true;
                if(run.cancelled||run.failure!=null||disposed||project.isDisposed()) {
                    abandon(run,failure,busy); return;
                }
                if(!run.finished.compareAndSet(false,true)) return;
                active.compareAndSet(run,null);
                busy.accept(false);
                @SuppressWarnings("unchecked") T value=(T)run.result;
                result.accept(value);
            }
        }.queue();
    }
    private void abandon(Run run,Consumer<String> failure,Consumer<Boolean> busy) {
        if(!run.workerCompleted||!run.finished.compareAndSet(false,true)) return;
        // Disposal may run on the EDT; Docker rollback must never do so.
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            String message=run.failure==null?"Cancelled. Work already accepted by a remote service may continue; refresh to verify.":
                    (run.failure.getMessage()==null?run.failure.toString():run.failure.getMessage());
            if(run.abandon!=null) {
                try { String cleanup=run.abandon.get(); if(cleanup!=null&&!cleanup.isBlank()) message+="\n\n"+cleanup; }
                catch(Throwable e) { message+="\nResource cleanup failed: "+e; }
            }
            active.compareAndSet(run,null);
            String displayed=message;
            if(!disposed&&!project.isDisposed()) ApplicationManager.getApplication().invokeLater(()->{
                if(!disposed&&!project.isDisposed()) { busy.accept(false); failure.accept(displayed); }
            });
        });
    }
    @Override public void dispose() {
        disposed=true;
        var run=active.get();
        if(run!=null) { run.cancelled=true; abandon(run,ignored->{},ignored->{}); }
    }
}
