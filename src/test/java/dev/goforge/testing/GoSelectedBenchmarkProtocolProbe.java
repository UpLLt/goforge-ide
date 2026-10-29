package dev.goforge.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in generated-fixture AST -> selection -> actual Go benchmark -> event pipeline proof. */
public final class GoSelectedBenchmarkProtocolProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Supply an empty evidence directory.");
        Path out=Path.of(args[0]).toAbsolutePath();Files.createDirectories(out);
        try(var files=Files.list(out)){if(files.findAny().isPresent())throw new IllegalArgumentException("Evidence directory must be empty.");}
        Path go=Path.of(System.getProperty("user.dir"),".tools","bin","go");
        var env=new HashMap<>(System.getenv());env.keySet().removeIf(k->k.toUpperCase(Locale.ROOT).startsWith("GO"));
        env.putAll(Map.of("GOENV","off","GOWORK","off","GOFLAGS","","GOPROXY","off","GOSUMDB","off","GOTOOLCHAIN","local","CGO_ENABLED","0","GOTELEMETRY","off","GOPACKAGESDRIVER","off"));
        var result=new LinkedHashMap<String,Object>();result.put("passed",false);result.put("scope","Actual source indexer, decoder, selection and Go test benchmark process on generated fixtures; not native caret/Save/configuration UI acceptance.");
        var checks=new ArrayList<Object>();result.put("discoveryChecks",checks);
        try{
            inspect(go,env,out,checks,"default-import","import \"testing\"\nfunc BenchmarkOne(b *testing.B) {}","benchmark");
            inspect(go,env,out,checks,"alias-and-parameter-name","import t \"testing\"\nfunc BenchmarkOne(t *t.B) {}","benchmark");
            inspect(go,env,out,checks,"unnamed-parameter","import \"testing\"\nfunc BenchmarkOne(*testing.B) {}","benchmark");
            inspect(go,env,out,checks,"fake-package","import \"other\"\nfunc BenchmarkOne(b *other.B) {}","unsupported-benchmark");
            inspect(go,env,out,checks,"dot-import","import . \"testing\"\nfunc BenchmarkOne(b *B) {}","unsupported-benchmark");
            inspect(go,env,out,checks,"type-alias","import \"testing\"\ntype B = testing.B\nfunc BenchmarkOne(b *B) {}","unsupported-benchmark");
            inspect(go,env,out,checks,"wrong-type","import \"testing\"\nfunc BenchmarkOne(b *testing.T) {}","unsupported-benchmark");
            inspect(go,env,out,checks,"multiple-names","import \"testing\"\nfunc BenchmarkOne(a,b *testing.B) {}","unsupported-benchmark");
            inspect(go,env,out,checks,"generic","import \"testing\"\nfunc BenchmarkOne[T any](b *testing.B) {}","unsupported-benchmark");
            inspect(go,env,out,checks,"results","import \"testing\"\nfunc BenchmarkOne(b *testing.B) int {return 0}","unsupported-benchmark");
            inspect(go,env,out,checks,"missing-body","import \"testing\"\nfunc BenchmarkOne(b *testing.B)","unsupported-benchmark");
            inspect(go,env,out,checks,"shadowed-import","import \"testing\"\ntype testing struct{}\nfunc BenchmarkOne(b *testing.B) {}","unsupported-benchmark");
            inspect(go,env,out,checks,"duplicate-alias","import (t \"testing\";t \"other\")\nfunc BenchmarkOne(b *t.B) {}","unsupported-benchmark");
            String duplicate="package p\nimport \"testing\"\nfunc BenchmarkOne(b *testing.B) {}\nfunc BenchmarkOne(b *testing.B) {}\n";
            try{GoTestSourceIndex.inspect(go.toString(),env,out.resolve("duplicate_test.go"),duplicate,()->false);throw new AssertionError("Duplicate benchmark accepted.");}
            catch(java.io.IOException correct){checks.add(Map.of("case","duplicate-name","refused",true));}
            String source="""
                package fixture
                import "testing"
                var Sink int
                // 中文🙂 offsets and selected body are preserved.
                func BenchmarkOne(b *testing.B) { b.ReportAllocs(); for i:=0;i<b.N;i++ { Sink+=i } }
                func BenchmarkTwo(b *testing.B) { b.Fatal("BenchmarkTwo must not run") }
                func TestOrdinary(t *testing.T) { t.Fatal("ordinary tests must not run") }
                """;
            Path fixture=Files.createDirectory(out.resolve("fixture"));Files.writeString(fixture.resolve("go.mod"),"module example.test/selectedbenchmark\n\ngo 1.26\n");
            Path file=fixture.resolve("selected_test.go");Files.writeString(file,source);
            var snapshot=GoTestSourceIndex.inspect(go.toString(),env,file,source,()->false);
            var declaration=snapshot.containing(source.indexOf("Sink+=i"));
            var selection=GoTestSelection.declaration(declaration.name(),declaration.kind());
            if(!selection.equals(GoTestSelection.benchmark("BenchmarkOne")))throw new AssertionError("Caret selected the wrong declaration.");
            var argsList=new ArrayList<String>(List.of(go.toString(),"test","-json","-benchtime=1x","-benchmem"));
            GoTestSelector.validateArguments(List.of("-benchtime=1x","-benchmem"),"");argsList.addAll(GoTestSelector.arguments(selection));argsList.add(".");
            result.put("argv",argsList);result.put("selection",selection);result.put("declarations",snapshot.declarations());
            var builder=new ProcessBuilder(argsList).directory(fixture.toFile()).redirectOutput(out.resolve("benchmark.jsonl").toFile()).redirectError(out.resolve("benchmark.stderr").toFile());builder.environment().clear();builder.environment().putAll(env);
            Process process=builder.start();
            try{if(!process.waitFor(45,TimeUnit.SECONDS))throw new AssertionError("Benchmark timed out.");if(process.exitValue()!=0)throw new AssertionError("Selected benchmark failed.");}
            finally{if(process.isAlive()){process.descendants().forEach(p->p.destroyForcibly());process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}result.put("processAliveAfterCleanup",process.isAlive());}
            var selectedNames=new GoTestSelectionObservation(selection,Set.of("example.test/selectedbenchmark"));
            var converter=new GoTestEvents((pkg,name,node,inferred)->{selectedNames.location(pkg,name,inferred);return "";});
            var emissions=new ArrayList<GoTestEvents.Emission>();var observed=new TreeSet<String>();var json=new Gson();
            String output=Files.readString(out.resolve("benchmark.jsonl"));
            for(String line:output.lines().toList()){
                @SuppressWarnings("unchecked") Map<String,Object> event=json.fromJson(line,Map.class);
                if(event.get("Test") instanceof String name)observed.add(name);emissions.addAll(converter.accept(event));
            }
            // This is the production handler's finalization order, including its registry's
            // shared observation state. The IDE process-handler/SM lifecycle still needs platform tests.
            emissions.addAll(converter.selectedTestsNotObserved(selectedNames.missing()));
            emissions.addAll(converter.finishProcess(0));
            if(!selectedNames.missing().isEmpty())throw new AssertionError("Successful selected benchmark was reported missing.");
            if(!observed.equals(Set.of("BenchmarkOne")))throw new AssertionError("Unexpected test/benchmark events: "+observed);
            if(!output.contains("ns/op")||!output.contains("B/op")||!output.contains("allocs/op"))throw new AssertionError("Benchmark metrics missing.");
            long completed=emissions.stream().filter(e->e.kind()==GoTestEvents.Kind.TEST_FINISHED&&e.status()==GoTestEvents.Status.BENCHMARK_COMPLETED).count();
            if(completed!=1||emissions.stream().anyMatch(e->e.kind()==GoTestEvents.Kind.TEST_FAILED||e.kind()==GoTestEvents.Kind.INFRASTRUCTURE_FAILURE||e.kind()==GoTestEvents.Kind.PROTOCOL_WARNING))throw new AssertionError("Benchmark terminal events were incorrect.");
            if(!Files.readString(file).equals(source))throw new AssertionError("Source changed during discovery/run.");
            result.put("observedNames",observed);result.put("missingSelectedNames",selectedNames.missing());result.put("benchmarkCompletedEvents",completed);result.put("iterationsRequested",1);result.put("ordinaryTestsExecuted",false);result.put("passed",true);
            System.out.println(checks.size()+" discovery checks and one real selected benchmark execution passed.");
        }catch(Exception|Error error){result.put("failure",error.toString());throw error;}
        finally{Files.writeString(out.resolve("result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");}
    }
    private static void inspect(Path go,Map<String,String> env,Path out,List<Object> checks,String name,String body,String kind)throws Exception{
        String source="package p\n"+body+"\nfunc TestOrdinary(t *testing.T) {}\n";
        var snapshot=GoTestSourceIndex.inspect(go.toString(),env,out.resolve(name+"_test.go"),source,()->false);
        var benchmark=snapshot.named("BenchmarkOne");
        if(benchmark==null||!kind.equals(benchmark.kind())||snapshot.named("TestOrdinary")==null)throw new AssertionError("Wrong benchmark discovery: "+name);
        checks.add(Map.of("case",name,"kind",benchmark.kind(),"source",source,"passed",true));
    }
}
