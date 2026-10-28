package dev.goforge.containers;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Uses the exact product session backend, not probe-owned cleanup as the acceptance condition. */
public final class DevContainerSessionProbe {
    public static void main(String[] args)throws Exception {
        String docker=args[0],context=args[1],node=args[2];Path cli=Path.of(args[3]),root=Path.of(args[4]),report=Path.of(args[5]);
        var env=new HashMap<>(System.getenv());env.put("DOCKER_CONTEXT",context);
        var checks=new LinkedHashMap<String,Boolean>();var sessions=new ArrayList<DevContainerSession>();var errors=new ArrayList<String>();var plans=new ArrayList<DevContainerPlan>();
        try {
            Files.createDirectories(root.resolve(".devcontainer"));Path config=root.resolve(".devcontainer/devcontainer.json");
            Files.writeString(root.resolve("Dockerfile"),"FROM node:24-slim\nLABEL dev.goforge.fixture=\"session-lifecycle\"\n");
            Files.writeString(config,"{\"build\":{\"context\":\"..\",\"dockerfile\":\"../Dockerfile\"}}");
            var plan=DevContainerPlan.read(root,config,"build-cycle");plans.add(plan);var session=new DevContainerSession();sessions.add(session);
            var first=session.begin(plan,docker,context,env);var started=session.start(first,node,cli,root.resolve("generated/build/devcontainer.json"),()->false);session.commit(first);
            String firstTag=started.state().image().tag;
            checks.put("build_actual_product_session",session.state()!=null&&firstTag!=null);
            session.stop(()->false);
            var next=session.begin(plan,docker,context,env);var restarted=session.start(next,node,cli,root.resolve("generated/build/devcontainer.json"),()->false);session.commit(next);
            checks.put("stop_start_reuses_same_container_image_tag",started.state().id().equals(restarted.state().id())&&firstTag.equals(restarted.state().image().tag)&&started.state().imageId().equals(restarted.state().imageId()));
            checks.put("restart_does_not_register_another_build",next.images.isEmpty());
            var reopened=new DevContainerSession();sessions.add(reopened);var coldAttempt=reopened.begin(plan,docker,context,env);
            var cold=reopened.start(coldAttempt,node,cli,root.resolve("generated/reopened/devcontainer.json"),()->false);reopened.commit(coldAttempt);
            checks.put("cross_session_build_reconnect_without_new_tag",coldAttempt.images.isEmpty()&&cold.state().id().equals(started.state().id())&&firstTag.equals(cold.state().image().tag));
            checks.put("cold_session_recovers_exact_immutable_image_ownership",cold.state().imageId().equals(started.state().imageId())&&cold.state().image().session.equals(started.state().image().session));
            Files.writeString(report.resolveSibling("build-remove.log"),reopened.remove(()->false));
            checks.put("remove_clears_worker_state",reopened.state()==null&&!reopened.hasResources());
            session.remove(()->false); // The prior process-local view can safely observe already-removed identities.
            checks.put("original_tag_removed",!inspectImage(docker,context,root,env,firstTag).succeeded());
            checks.put("build_container_absent",containers(docker,context,root,env,plan.owner()).isBlank());

            var latePlan=DevContainerPlan.read(root,config,"late-cancel");plans.add(latePlan);var late=new DevContainerSession();sessions.add(late);
            var attempt=late.begin(latePlan,docker,context,env);var uncommitted=late.start(attempt,node,cli,root.resolve("generated/late/devcontainer.json"),()->false);
            checks.put("worker_identity_exists_before_ui_commit",late.state()==null&&late.hasResources()&&attempt.createdIds.contains(uncommitted.state().id()));
            var rollback=late.abandon(attempt);Files.writeString(report.resolveSibling("late-cancel-cleanup.log"),rollback.output());
            checks.put("late_abandon_removes_container_and_build_tag",rollback.complete()&&containers(docker,context,root,env,latePlan.owner()).isBlank()&&!inspectImage(docker,context,root,env,uncommitted.state().image().tag).succeeded());
            checks.put("late_abandon_idempotent",late.abandon(attempt).complete()&&!late.hasResources());

            Files.writeString(root.resolve("Dockerfile"),"FROM scratch\nLABEL dev.goforge.fixture=\"session-failed-up\"\n");
            var failedPlan=DevContainerPlan.read(root,config,"failed-up");plans.add(failedPlan);var failed=new DevContainerSession();sessions.add(failed);
            var failing=failed.begin(failedPlan,docker,context,env);boolean rejected=false;
            try{failed.start(failing,node,cli,root.resolve("generated/failure/devcontainer.json"),()->false);}catch(Exception expected){rejected=true;Files.writeString(report.resolveSibling("failed-up.log"),expected.toString());}
            checks.put("real_up_failure_after_submission",rejected&&failing.upSubmitted&&!failing.upSucceeded);
            checks.put("failed_up_product_cleanup_no_container",containers(docker,context,root,env,failedPlan.owner()).isBlank());
            checks.put("failed_up_product_cleanup_no_owned_tag",failing.images.stream().allMatch(i->i.released));
            // A no-result daemon request can remain in the recovery ledger despite an empty inventory.
            if(failed.hasResources())Files.writeString(report.resolveSibling("failed-up-retry.log"),failed.remove(()->false));
            checks.put("failed_up_recovery_remove_clears_state",!failed.hasResources());

            Files.writeString(root.resolve("Dockerfile"),"FROM node:24-slim\nRUN exit 23\n");
            var badBuild=DevContainerPlan.read(root,config,"failed-build");plans.add(badBuild);var buildSession=new DevContainerSession();sessions.add(buildSession);var buildAttempt=buildSession.begin(badBuild,docker,context,env);
            boolean buildRejected=false;try{buildSession.start(buildAttempt,node,cli,root.resolve("generated/bad-build/devcontainer.json"),()->false);}catch(Exception expected){buildRejected=true;Files.writeString(report.resolveSibling("failed-build.log"),expected.toString());}
            checks.put("failed_build_tag_was_registered_before_process",buildRejected&&!buildAttempt.upSubmitted&&buildAttempt.images.size()==1);
            if(buildSession.hasResources())buildSession.remove(()->false);
            checks.put("failed_build_recovery_clears_only_owned_tag",!buildSession.hasResources()&&!inspectImage(docker,context,root,env,buildAttempt.images.getFirst().tag).succeeded());
            checks.put("external_base_image_retained",inspectImage(docker,context,root,env,"node:24-slim").succeeded());
            if(checks.values().stream().anyMatch(v->!v))throw new AssertionError(checks);
        } catch(Throwable e){errors.add(e.toString());}
        finally {
            for(var session:sessions)if(session.hasResources())try{session.remove(()->false);}catch(Exception e){errors.add("fallback cleanup: "+e);}
            for(var plan:plans)try{if(!containers(docker,context,root,env,plan.owner()).isBlank())errors.add("Owned containers remain: "+plan.owner());}catch(Exception e){errors.add("cleanup inventory: "+e);}
            var result=new LinkedHashMap<String,Object>();result.put("passed",errors.isEmpty());result.put("checks",checks);result.put("errors",errors);result.put("native_ui_verified",false);result.put("scope","Actual product backend with official CLI and own fixtures; late UI cancellation is separately covered by platform Task tests. No user image, container or context was mutated.");
            Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(result));
        }
        if(!errors.isEmpty())throw new AssertionError(errors);
    }
    private static DockerClient.Result inspectImage(String docker,String context,Path root,Map<String,String> env,String tag)throws Exception{return DockerClient.execute(docker,context,new DockerCommands.Request("Inspect exact fixture tag",List.of("image","inspect","--",tag),Duration.ofSeconds(30),false),root,env,()->false);}
    private static String containers(String docker,String context,Path root,Map<String,String> env,String owner)throws Exception{return DockerClient.execute(docker,context,new DockerCommands.Request("Inspect exact fixture owner",List.of("container","ls","--all","--quiet","--no-trunc","--filter","label=dev.goforge.workspace="+owner),Duration.ofSeconds(30),false),root,env,()->false).requiredJson();}
}
