package dev.goforge.refactor;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

public final class GoChangeScalarTypeTest {
    @Test public void specificationDistinguishesParameterAndSingleResult() {
        assertEquals("int64",new GoChangeScalarType.Spec(false,1," int64 ").type());
        assertTrue(new GoChangeScalarType.Spec(true,0,"string").result());
        assertThrows(IllegalArgumentException.class,()->new GoChangeScalarType.Spec(true,1,"int"));
        assertThrows(IllegalArgumentException.class,()->new GoChangeScalarType.Spec(false,-1,"int"));
        assertThrows(IllegalArgumentException.class,()->new GoChangeScalarType.Spec(false,0,null));
    }
    @Test public void compilerEnvironmentIsOfflineAndCannotInvokeDriversOrAutomaticToolchains() {
        var env=GoChangeScalarType.compileEnvironment(Map.of("GOFLAGS","-toolexec=bad","GOWORK","/outside/go.work","GOPACKAGESDRIVER","bad","GOTOOLCHAIN","auto","GOPROXY","https://example.invalid","CGO_ENABLED","1","GOMODCACHE","cache"));
        assertEquals("",env.get("GOFLAGS")); assertEquals("off",env.get("GOWORK"));
        assertEquals("off",env.get("GOENV")); assertEquals("off",env.get("GOPACKAGESDRIVER"));
        assertEquals("local",env.get("GOTOOLCHAIN")); assertEquals("off",env.get("GOPROXY"));
        assertEquals("1",env.get("CGO_ENABLED")); assertEquals("on",env.get("GO111MODULE"));
        assertEquals("cache",env.get("GOMODCACHE"));
    }
    @Test public void compilerPreservesEffectiveArchitectureFeaturesAndSelectedRoot() {
        var settings=Map.of("GOARM64","v9.0","GOAMD64","v3","GO386","softfloat","GOARM","7","GORISCV64","rva23u64","GOROOT","selected-sdk","GOFIPS140","latest");
        var environment=GoChangeScalarType.compileEnvironment(settings);
        for(var entry:settings.entrySet())assertEquals(entry.getValue(),environment.get(entry.getKey()));
    }
    @Test public void nativeDiscoveryCannotExecuteInheritedCompilerOrPackageConfigurationTools() throws Exception {
        Path root=fixture();try{
            var environment=new HashMap<>(Map.of("CC","user-cc","CXX","user-cxx","FC","user-fc","PKG_CONFIG","user-pkg"));
            GoChangeScalarType.disableNativeTools(environment,root.resolve("directory with spaces"));
            for(String key:List.of("CC","CXX","FC","PKG_CONFIG")) {
                String value=environment.get(key);assertTrue(value.startsWith("\""));assertTrue(value.endsWith("\""));
                assertFalse(Files.exists(Path.of(value.substring(1,value.length()-1))));assertTrue(value.contains("native-tools-disabled"));
            }
        }finally{remove(root);}
    }
    @Test public void cancellationCannotStartAnyCompilerOrQueryReferences() {
        assertThrows(CancellationException.class,()->GoChangeScalarType.plan("must-not-run",Map.of(),Path.of("."),List.of(),"file:///unused.go",new org.eclipse.lsp4j.Position(),new GoChangeScalarType.Spec(true,0,"int64"),x->{throw new AssertionError();},()->true));
    }
    @Test public void customBuildFlagsRefuseBeforeFileOrProcessAccess() {
        assertThrows(IllegalArgumentException.class,()->GoChangeScalarType.plan("must-not-run",Map.of("GOFLAGS","-tags=other"),Path.of("missing"),List.of(),"file:///unused.go",new org.eclipse.lsp4j.Position(),new GoChangeScalarType.Spec(true,0,"int64"),x->{throw new AssertionError();},()->false));
    }
    @Test public void moduleContentMutationInvalidatesCompileProofEvenAtSameSize() throws Exception {
        Path root=fixture();try{
            var metadata=GoChangeScalarType.Metadata.capture(root,List.of(root.resolve("api/a.go")));metadata.requireCurrent();
            Files.writeString(root.resolve("go.mod"),"module example.test/other\n\ngo 1.26\n");
            assertThrows(IllegalStateException.class,metadata::requireCurrent);
        }finally{remove(root);}
    }
    @Test public void newlyAddedModuleSumInvalidatesCompileProof() throws Exception {
        Path root=fixture();try{
            var metadata=GoChangeScalarType.Metadata.capture(root,List.of(root.resolve("api/a.go")));
            Files.writeString(root.resolve("go.sum"),"new sum\n");assertThrows(IllegalStateException.class,metadata::requireCurrent);
        }finally{remove(root);}
    }
    @Test public void newlyAddedWorkspaceInvalidatesCompileProof() throws Exception {
        Path root=fixture();try{
            var metadata=GoChangeScalarType.Metadata.capture(root,List.of(root.resolve("api/a.go")));
            Files.writeString(root.resolve("go.work"),"go 1.26\nuse .\n");assertThrows(IllegalStateException.class,metadata::requireCurrent);
        }finally{remove(root);}
    }
    @Test public void nestedModuleAndActiveWorkspaceAreRefusedAtCapture() throws Exception {
        Path root=fixture();try{
            Files.createDirectories(root.resolve("api"));Files.writeString(root.resolve("api/go.mod"),"module nested\n");
            assertThrows(IllegalArgumentException.class,()->GoChangeScalarType.Metadata.capture(root,List.of(root.resolve("api/a.go"))));
            Files.delete(root.resolve("api/go.mod"));Files.writeString(root.resolve("go.work"),"go 1.26\nuse .\n");
            assertThrows(IllegalArgumentException.class,()->GoChangeScalarType.Metadata.capture(root,List.of(root.resolve("api/a.go"))));
        }finally{remove(root);}
    }
    @Test public void deletedMetadataCannotKeepTheCompileProof() throws Exception {
        Path root=fixture();try{
            var metadata=GoChangeScalarType.Metadata.capture(root,List.of(root.resolve("api/a.go")));
            Files.delete(root.resolve("go.mod"));assertThrows(IllegalStateException.class,metadata::requireCurrent);
        }finally{remove(root);}
    }
    @Test public void vendorTreesAreRefusedAndNewVendorEntriesInvalidateProof() throws Exception {
        Path root=fixture();try{
            var metadata=GoChangeScalarType.Metadata.capture(root,List.of(root.resolve("api/a.go")));
            Files.createDirectory(root.resolve("vendor"));
            assertThrows(IllegalStateException.class,metadata::requireCurrent);
            assertThrows(IllegalArgumentException.class,()->GoChangeScalarType.Metadata.capture(root,List.of(root.resolve("api/a.go"))));
        }finally{remove(root);}
    }
    @Test public void nonGoLinkageSourcesRefuseBeforeStartingTheCompiler() throws Exception {
        Path root=fixture();try{
            Path source=root.resolve("a.go");String text="package p\nfunc F(n int){}\n";Files.writeString(source,text);Files.writeString(root.resolve("linkage.s"),"// excluded native caller\n");
            var snapshot=List.of(new GoRefactorWorkspaceEdit.Source(source.toUri().toString(),text,0));
            assertThrows(IllegalArgumentException.class,()->GoChangeScalarType.plan("must-not-run",Map.of(),root,snapshot,source.toUri().toString(),new org.eclipse.lsp4j.Position(1,1),new GoChangeScalarType.Spec(false,0,"int64"),x->{throw new AssertionError();},()->false));
        }finally{remove(root);}
    }
    private static Path fixture() throws Exception {
        Path root=Files.createTempDirectory("goforge-scalar-metadata-test-").toRealPath();
        Files.writeString(root.resolve("go.mod"),"module example.test/scalar\n\ngo 1.26\n");return root;
    }
    private static void remove(Path path)throws Exception{try(var all=Files.walk(path)){for(var p:all.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
}
