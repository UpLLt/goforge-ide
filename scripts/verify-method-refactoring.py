#!/usr/bin/env python3
"""Opt-in real method-refactoring check using only a selected GoForge package and owned fixtures.

A development class overlay is explicitly reported as a control run, never package acceptance.
No IDE GUI or complete GoLand equivalence is established by this protocol check.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
from urllib.parse import unquote, urlparse
from urllib.request import url2pathname
import zipfile

ROOT=Path(__file__).resolve().parents[1]
PROBE='src/test/java/dev/goforge/refactor/GoMethodAddParameterProtocolProbe.java'
MAIN='dev.goforge.refactor.GoMethodAddParameterProtocolProbe'
EXPECTED_CASES=('value-imports','pointer-imports','dirty-unicode','init-and-testmain-not-run',
 'interface-old-refused','interface-new-refused','interface-alias-refused','constraint-refused',
 'anonymous-interface-refused','method-value-refused','method-expression-refused','promoted-refused',
 'public-method-refused','public-receiver-refused','generic-receiver-refused','alias-receiver-refused',
 'missing-reference-refused','outside-reference-refused','metadata-change-after-preview',
 'goenv-change-after-preview','sdk-change-after-preview','default-cgo-parameter','cancelled-after-references')

# The original 23-case API remains the default for programmatic callers.
CASE_GROUPS={
    'method-add': ('GoMethodAddParameterProtocolProbe', True, EXPECTED_CASES),
    'method-scalar': ('GoMethodScalarTypeProtocolProbe', True, ('value-imports', 'pointer-imports', 'parameter-incompatible-caller', 'parameter-incompatible-body', 'shadow-old-refused', 'shadow-new-refused', 'alias-shadow-refused', 'method-result-incompatible-body', 'dirty-unicode', 'init-and-testmain-not-run', 'interface-old-refused', 'interface-new-refused', 'interface-alias-refused', 'constraint-refused', 'anonymous-interface-refused', 'method-value-refused', 'method-expression-refused', 'promoted-refused', 'public-method-refused', 'public-receiver-refused', 'generic-receiver-refused', 'alias-receiver-refused', 'missing-reference-refused', 'outside-reference-refused', 'metadata-change-after-preview', 'goenv-change-after-preview', 'sdk-change-after-preview', 'default-cgo-parameter', 'cancelled-after-references')),
    'free-scalar': ('GoChangeScalarTypeProtocolProbe', True, ('goenv-change-after-preview', 'sdk-change-after-preview', 'default-cgo-parameter', 'parameter-default-import', 'alias-shadow-refused', 'parameter-constant', 'result-inferred', 'named-result', 'generic-parameter', 'generic-result', 'dirty-unicode', 'init-and-testmain-not-run', 'parameter-incompatible-caller', 'result-incompatible-consumer', 'result-incompatible-body', 'function-value-refused', 'missing-reference-refused', 'outside-reference-refused', 'metadata-change-after-preview', 'cancelled-after-references')),
}

REORDER_CASES=('shared-state', 'static-imported-argument', 'constants-exact-conversion', 'typed-float-argument', 'byte-rune-aliases', 'temporary-name-collision', 'panic-first-argument', 'recover-in-argument', 'dirty-unicode', 'init-and-testmain-not-run', 'mutable-variable-refused', 'function-variable-refused', 'shadowed-function-refused', 'method-argument-refused', 'conversion-refused', 'builtin-refused', 'caller-type-shadow-refused', 'declaration-alias-refused', 'function-value-refused', 'return-refused', 'go-refused', 'defer-refused', 'label-refused', 'tuple-refused', 'ellipsis-refused', 'generic-argument-refused', 'missing-reference-refused', 'outside-reference-refused', 'metadata-change-after-preview', 'sdk-change-after-preview', 'cancelled-after-references')
REORDER_BEHAVIOR={'shared-state': 'g|h|1,11|', 'static-imported-argument': 'h|-1,10|', 'constants-exact-conversion': '0.300000012,1099511627776|', 'typed-float-argument': 'g|h|0.300000012,1099511627776|', 'byte-rune-aliases': '255,30028|', 'temporary-name-collision': 'g|h|1,11|', 'panic-first-argument': 'g|boom1', 'recover-in-argument': 'g-recover:<nil>|h|1,11|boom', 'dirty-unicode': 'g|h|1,11|'}
CASE_GROUPS['free-function-reorder']=('GoFunctionReorderProtocolProbe', True, REORDER_CASES)
METHOD_RESULT_CASES=('unnamed-constant', 'named-result', 'named-deferred-result', 'zero-named-result', 'pointer-result', 'byte-rune-result', 'generic-caller', 'same-line-unicode-caller', 'dirty-unicode', 'init-and-testmain-not-run', 'result-incompatible-caller', 'result-incompatible-body', 'constant-overflow', 'multiple-results-refused', 'grouped-results-refused', 'no-result-refused', 'named-type-refused', 'shadow-old-refused', 'shadow-new-refused', 'alias-shadow-refused', 'interface-old-refused', 'interface-new-refused', 'interface-alias-refused', 'constraint-refused', 'anonymous-interface-refused', 'method-value-refused', 'method-expression-refused', 'promoted-refused', 'public-method-refused', 'public-receiver-refused', 'generic-receiver-refused', 'alias-receiver-refused', 'missing-reference-refused', 'outside-reference-refused', 'metadata-change-after-preview', 'goenv-change-after-preview', 'sdk-change-after-preview', 'cancelled-after-references')
METHOD_RESULT_BEHAVIOR={'unnamed-constant': {'before': 'result=int:7', 'after': 'result=int64:7'}, 'named-result': {'before': 'result=int:7', 'after': 'result=int64:7'}, 'named-deferred-result': {'before': 'result=int:7', 'after': 'result=int64:7'}, 'pointer-result': {'before': 'result=int:7', 'after': 'result=int64:7'}, 'generic-caller': {'before': 'result=int:7', 'after': 'result=int64:7'}, 'same-line-unicode-caller': {'before': 'result=int:7', 'after': 'result=int64:7'}, 'dirty-unicode': {'before': 'result=int:7', 'after': 'result=int64:7'}, 'zero-named-result': {'before': 'result=int:0', 'after': 'result=int64:0'}, 'byte-rune-result': {'before': 'result=uint8:65', 'after': 'result=int32:65'}}
CASE_GROUPS['method-result']=('GoMethodResultTypeProtocolProbe', True, METHOD_RESULT_CASES)
SUITE_GROUPS=('method-add','method-scalar','free-scalar','free-function-reorder','method-result')
SUITE_CASE_IDS=tuple('dev.goforge.refactor.'+name+'#'+case
                     for name,_,cases in CASE_GROUPS.values() for case in cases)

NATIVE_BOOTSTRAP='GoForgePackagedNativeProbe'
NATIVE_SOURCE='''import java.nio.file.*;
import java.util.*;
import com.google.gson.GsonBuilder;
public final class GoForgePackagedNativeProbe {
  public static void main(String[] args) throws Exception {
    var report=new LinkedHashMap<String,Object>();report.put("passed",false);
    try {
      Path expected=Path.of(args[2]).toRealPath(),provider=Path.of(args[3]).toRealPath();
      Class<?> nativeClass=Class.forName("com.sun.jna.Native");
      var field=nativeClass.getDeclaredField("jnidispatchPath");field.setAccessible(true);
      Path actual=Path.of((String)field.get(null)).toRealPath();
      Path actualProvider=Path.of(nativeClass.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
      report.put("dispatchPath",actual.toString());report.put("providerPath",actualProvider.toString());
      report.put("bootLibraryPath",System.getProperty("jna.boot.library.path"));
      report.put("noSystemFallback",Boolean.getBoolean("jna.nosys"));report.put("noClasspathFallback",Boolean.getBoolean("jna.noclasspath"));
      if(!expected.equals(actual)||!provider.equals(actualProvider)
          ||!expected.getParent().equals(Path.of(System.getProperty("jna.boot.library.path")).toRealPath())
          ||!Boolean.getBoolean("jna.nosys")||!Boolean.getBoolean("jna.noclasspath"))
        throw new IllegalStateException("The native identity reader did not load the exact bundled JNA dispatch.");
      report.put("passed",true);
    } catch(Exception|Error failure) {report.put("failure",failure.toString());throw failure;}
    finally {Files.writeString(Path.of(args[4]),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\\n");}
    Class.forName(args[0]).getMethod("main",String[].class).invoke(null,(Object)new String[]{args[1]});
  }
}
'''


def sha256(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()


def package_layout(app):
    app=Path(app).resolve(strict=True)
    home=app/'Contents' if (app/'Contents/lib').is_dir() else app
    jbr=home/'jbr/Contents/Home' if (home/'jbr/Contents/Home').is_dir() else home/'jbr'
    suffix='.exe' if os.name=='nt' else ''
    paths={'home':home,'jbr':jbr,'java':jbr/'bin'/('java'+suffix),
           'javac':jbr/'bin'/('javac'+suffix),'go':home/'goforge-tools/go/bin'/('go'+suffix),
           'gopls':home/'goforge-tools/bin'/('gopls'+suffix)}
    for name in ('java','javac','go','gopls'):
        path=paths[name].resolve(strict=True)
        if not path.is_file() or not path.is_relative_to(home.resolve()):
            raise RuntimeError('Expected bundled regular executable: '+name)
        paths[name]=path
    goforge=sorted((home/'plugins/goforge/lib').glob('*.jar'))
    providers=[]
    for jar in goforge:
        with zipfile.ZipFile(jar) as archive:
            if 'dev/goforge/refactor/GoAddParameter.class' in archive.namelist():providers.append(jar.resolve())
    if len(providers)!=1:raise RuntimeError('Expected exactly one packaged GoForge planner provider')
    paths['plugin']=providers[0]
    lsp=home/'plugins/lsp4ij/lib'
    paths['lsp']=lsp/'org.eclipse.lsp4j-1.0.0.jar'
    paths['jsonrpc']=lsp/'org.eclipse.lsp4j.jsonrpc-1.0.0.jar'
    for name in ('lsp','jsonrpc'):
        if not paths[name].is_file():raise RuntimeError('Pinned package LSP4J model is missing: '+name)
        paths[name]=paths[name].resolve()
    jars=[paths['plugin'],paths['lsp'],paths['jsonrpc']]+goforge+sorted(lsp.glob('*.jar'))+sorted((home/'lib').glob('*.jar'))
    paths['jars']=list(dict.fromkeys(p.resolve() for p in jars))
    if any(not p.is_relative_to(home.resolve()) for p in paths['jars']):raise RuntimeError('Package classpath escapes the app')
    paths.update(native_layout(home,jbr))
    return paths


def native_layout(home,jbr):
    home=Path(home).resolve(strict=True);release=Path(jbr)/'release'
    match=re.search(r'^OS_ARCH="([^"]+)"$',release.read_text(encoding='utf-8'),re.MULTILINE)
    architecture={'amd64':'amd64','x86_64':'amd64','aarch64':'aarch64','arm64':'aarch64'}.get(match.group(1) if match else '')
    if architecture is None:raise RuntimeError('Unsupported packaged JBR architecture for the JNA identity reader')
    filename='jnidispatch.dll' if os.name=='nt' else 'libjnidispatch.jnilib' if sys.platform=='darwin' else 'libjnidispatch.so'
    paths={'jnaDispatch':home/'lib/jna'/architecture/filename,'jnaProvider':home/'lib/util-8.jar','jbrRelease':release}
    for name,path in paths.items():
        paths[name]=path.resolve(strict=True)
        if not paths[name].is_file() or not paths[name].is_relative_to(home):raise RuntimeError('Packaged native identity input escapes the app: '+name)
    return paths


def native_arguments(layout):
    return ['-Djna.boot.library.path='+str(layout['jnaDispatch'].parent),'-Djna.boot.library.name=jnidispatch',
            '-Djna.nosys=true','-Djna.noclasspath=true']


def require_native_result(result,layout):
    if result.get('passed') is not True or result.get('noSystemFallback') is not True or result.get('noClasspathFallback') is not True:
        raise RuntimeError('Bundled native identity reader failed or allowed a fallback')
    for key,expected in [('dispatchPath',layout['jnaDispatch']),('providerPath',layout['jnaProvider']),('bootLibraryPath',layout['jnaDispatch'].parent)]:
        if Path(result.get(key,'')).resolve(strict=True)!=expected.resolve():raise RuntimeError('Native identity reader differs from the selected package: '+key)
    return result


def probe_environment(layout,owned,inherited):
    blocked={'JAVA_HOME','JDK_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'}
    env={k:v for k,v in inherited.items() if not k.upper().startswith(('GO','IDEA_')) and k.upper() not in blocked}
    # Own fixture/Java temporary paths and requested project caches; production helpers may reset Go cache overrides.
    for k in list(env):
        if k.upper() in ('PATH','TMP','TEMP','TMPDIR'):env.pop(k)
    inherited_path=next((v for k,v in inherited.items() if k.upper()=='PATH'),'')
    env.update(PATH=os.pathsep.join([str(layout['go'].parent),str(layout['gopls'].parent),inherited_path]),
               TMP=str(owned/'tmp'),TEMP=str(owned/'tmp'),TMPDIR=str(owned/'tmp'),
               GOENV='off',GOWORK='off',GOTOOLCHAIN='local',GOPROXY='off',GOSUMDB='off',
               GOTELEMETRY='off',GOPACKAGESDRIVER='off',GOVCS='*:off',CGO_ENABLED='0',
               GOCACHE=str(owned/'go-cache'),GOMODCACHE=str(owned/'go-modcache'),GOPATH=str(owned/'gopath'))
    return env


def quote_argument(value):
    text=str(value)
    if any(c in text for c in '\r\n\0'):raise ValueError('Unexpected line break/NUL in a Java argument')
    return '"'+text.replace('\\','\\\\').replace('"','\\"')+'"'


def run_java_tool(executable,args,report,name,env,cwd,timeout):
    arguments=report/(name+'.args');arguments.write_text('\n'.join(map(quote_argument,args))+'\n',encoding='utf-8')
    command=[str(executable),'@'+str(arguments)]
    started=time.monotonic();exit_code=None;timed_out=False;cleanup=None;failure=None
    try:
        with (report/(name+'.log')).open('wb') as output:
            flags={'creationflags':subprocess.CREATE_NEW_PROCESS_GROUP} if os.name=='nt' else {'start_new_session':True}
            process=subprocess.Popen(command,cwd=cwd,env=env,stdout=output,stderr=subprocess.STDOUT,**flags)
            try:exit_code=process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out=True
                cleanup={}
                try:
                    if os.name=='nt':
                        system=next((v for k,v in env.items() if k.upper()=='SYSTEMROOT'),None)
                        if system:
                            killed=subprocess.run([str(Path(system)/'System32/taskkill.exe'),'/PID',str(process.pid),'/T','/F'],capture_output=True,timeout=20)
                            cleanup={'taskkillExit':killed.returncode,'output':killed.stdout.decode('utf-8','replace')[-2000:]}
                            if killed.returncode!=0:raise RuntimeError('taskkill did not report successful tree termination')
                        else:raise RuntimeError('SystemRoot absent; cannot locate the Windows tree terminator')
                    else:
                        try:os.killpg(process.pid,signal.SIGKILL)
                        except ProcessLookupError:pass
                        cleanup={'signal':'SIGKILL to owned process group'}
                except Exception as termination_failure:
                    cleanup['failure']=type(termination_failure).__name__+': '+str(termination_failure)
                    # This fallback targets only the owned JVM; no descendant-exit claim is made.
                    try:process.kill();cleanup['fallbackOwnedJvmOnly']=True
                    except Exception as kill_failure:cleanup['fallbackFailure']=type(kill_failure).__name__+': '+str(kill_failure)
                try:exit_code=process.wait(timeout=15)
                except subprocess.TimeoutExpired:cleanup['stillAlive']=True
    except Exception as error:
        failure=error
    finally:
        receipt={'executable':str(executable),'argumentsFile':arguments.name,'exit':exit_code,'timedOut':timed_out,'seconds':round(time.monotonic()-started,3),'cleanup':cleanup}
        if failure is not None:receipt['failure']=type(failure).__name__+': '+str(failure)
        (report/(name+'-process.json')).write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
    if failure is not None:raise RuntimeError(name+' failed; see '+name+'-process.json') from failure
    if timed_out or exit_code!=0:raise RuntimeError(name+' did not finish successfully; see '+name+'.log')
    return receipt


def uri_file(value):
    parsed=urlparse(value)
    if parsed.scheme!='file' or parsed.query or parsed.fragment or parsed.netloc not in ('','localhost'):
        raise RuntimeError('Expected a local code-source URI')
    return Path(url2pathname(parsed.path)).resolve(strict=True)


def require_results(result,layout,overlay=None,*,expected_cases=EXPECTED_CASES,require_ready=True,source_preservation=None):
    checks=result.get('checks')
    if result.get('passed') is not True or not isinstance(checks,list):raise RuntimeError('Method probe did not report success')
    names=[x.get('case') for x in checks]
    if len(names)!=len(expected_cases) or set(names)!=set(expected_cases):raise RuntimeError('Method probe did not execute the exact '+str(len(expected_cases))+' cases')
    for item in checks:
        if item.get('passed') is not True or (require_ready and item.get('versionedDiagnosticsReady') is not True) or item.get('fixtureDeleted') is not True or item.get('goplsAliveAfterCleanup') is not False or item.get('goplsExit')!=0 or 'cleanupFailure' in item or 'failure' in item or 'suppressedFailures' in item:
            raise RuntimeError('Method case failed readiness, execution or cleanup: '+str(item.get('case')))
        if source_preservation is not None and item.get(source_preservation) is not True:
            raise RuntimeError('Method case failed source preservation: '+str(item.get('case')))
    provider=Path(overlay).resolve(strict=True) if overlay else layout['plugin'].resolve()
    for key in ('plannerCodeSource','typedProofCodeSource','compilerProofCodeSource'):
        if uri_file(result.get(key,''))!=provider:raise RuntimeError('Method implementation was not loaded from the expected provider: '+key)
    if uri_file(result.get('lspModelCodeSource',''))!=layout['lsp']:raise RuntimeError('Probe did not use the packaged pinned LSP model')
    for key,expected in [('javaHome',layout['jbr']),('goExecutable',layout['go']),('goplsExecutable',layout['gopls'])]:
        if Path(result.get(key,'')).resolve(strict=True)!=expected.resolve():raise RuntimeError('Probe tool identity differs: '+key)
    return {'cases':len(checks),'packagedFeaturePassed':overlay is None,'developmentOverlayOnly':overlay is not None}


def require_reorder_behavior(result):
    """Keep the nine independent expected outputs fixed, not merely equal to one another."""
    checks=result.get('checks')
    if result.get('passed') is not True or not isinstance(checks,list) or len(checks)!=31 or {c.get('case') for c in checks}!=set(REORDER_CASES):
        raise RuntimeError('Reorder protocol did not return the exact 31 cases')
    comparisons={}
    for item in checks:
        case=item['case']
        if item.get('passed') is not True or item.get('versionedDiagnosticsReady') is not True or item.get('fixtureDeleted') is not True or item.get('goplsAliveAfterCleanup') is not False or item.get('goplsExit')!=0 or item.get('originalSourceBytesUnchangedOrRestoredAfterExplicitFixtureTest') is not True or any(key in item for key in ('failure','cleanupFailure','suppressedFailures')):
            raise RuntimeError('Reorder case failed readiness, source or cleanup: '+case)
        if case in REORDER_BEHAVIOR:
            expected=REORDER_BEHAVIOR[case]
            if item.get('fixtureAppliedExecutedAndRestored') is not True or item.get('beforeOutput')!=expected or item.get('afterOutput')!=expected or item.get('expectedOutput')!=expected:
                raise RuntimeError('Reorder fixed behavior output differs: '+case)
            comparisons[case]=item['afterOutput']
        elif item.get('fixtureAppliedExecutedAndRestored'):
            raise RuntimeError('Unexpected reorder behavior execution claim: '+case)
    if comparisons!=REORDER_BEHAVIOR:raise RuntimeError('Missing fixed reorder behavior comparison')
    return comparisons


def require_method_result_behavior(result):
    """Require independently fixed runtime types/values, not merely self-reported equal markers."""
    checks=result.get('checks')
    if result.get('passed') is not True or not isinstance(checks,list) or len(checks)!=38 or {c.get('case') for c in checks}!=set(METHOD_RESULT_CASES):
        raise RuntimeError('Method result protocol did not return the exact 38 cases')
    comparisons={};messages=0;warnings=0
    for item in checks:
        case=item['case']
        if item.get('passed') is not True or item.get('versionedDiagnosticsReady') is not True or item.get('fixtureDeleted') is not True or item.get('goplsAliveAfterCleanup') is not False or item.get('goplsExit')!=0 or item.get('originalSourceBytesUnchangedOrRestoredAfterExplicitFixtureTest') is not True or any(key in item for key in ('failure','cleanupFailure','suppressedFailures')):
            raise RuntimeError('Method result case failed readiness, source or cleanup: '+case)
        logged=item.get('serverMessages')
        if not isinstance(logged,list) or len(logged)>100 or any(not isinstance(message,str) or len(message)>2000 for message in logged):
            raise RuntimeError('Method result server messages are missing or malformed: '+case)
        messages+=len(logged);warnings+=sum('orphaned files' in message.lower() for message in logged)
        if case in METHOD_RESULT_BEHAVIOR:
            expected=METHOD_RESULT_BEHAVIOR[case]
            if item.get('fixtureAppliedTestedAndRestored') is not True or type(item.get('changedFiles')) is not int or item['changedFiles']!=1 or type(item.get('compiledPackagesBeforeAndAfter')) is not int or item['compiledPackagesBeforeAndAfter']!=1 or item.get('compileOnlyProjectCodeNotExecuted') is not True:
                raise RuntimeError('Method result positive execution/compile proof is missing: '+case)
            for phase in ('before','after'):
                output=item.get(phase+'GoTestOutput')
                marker=item.get('expected'+phase.title()+'Marker')
                actual=re.findall(r'^[ \t]+method_test\.go:\d+: (result=[^\r\n]*)\r?$',output,re.MULTILINE) if isinstance(output,str) else []
                if marker!=expected[phase] or actual!=[expected[phase]] or not re.search(r'^--- PASS: TestCall \(',output,re.MULTILINE) or not re.search(r'^PASS\r?$',output,re.MULTILINE) or re.search(r'^(?:--- )?FAIL',output,re.MULTILINE):
                    raise RuntimeError('Method result fixed runtime type/value differs: '+case+' '+phase)
            comparisons[case]=dict(expected)
        elif any(key in item for key in ('fixtureAppliedTestedAndRestored','beforeGoTestOutput','afterGoTestOutput','expectedBeforeMarker','expectedAfterMarker')):
            raise RuntimeError('Unexpected method result fixture execution claim: '+case)
    if comparisons!=METHOD_RESULT_BEHAVIOR:raise RuntimeError('Missing fixed method result comparison')
    return {'comparisons':comparisons,'serverMessageCount':messages,'orphanedFileWarningCount':warnings}


def group_progress(event,group,planned,completed,started,status=None):
    # Only fixed group identifiers/counts and elapsed time: no environment or fixture paths.
    message='[method-refactoring] '+event+' group='+group+' cases='+str(planned)+' completed='+str(completed)+'/141 elapsed='+format(time.monotonic()-started,'.3f')+'s'
    if status is not None:message+=' status='+status
    print(message,flush=True)


class PhaseRecorder:
    """Bounded diagnostic state; never a protocol success or cleanup attestation."""
    PHASES=('layout','hash','temp-allocation','javac','jvm','result-validation','temp-cleanup')

    def __init__(self,report,group):
        if group not in SUITE_GROUPS:raise ValueError('Unknown diagnostic group')
        self.report=Path(report);self.group=group;self.started=time.monotonic();self.events=[];self.failure=None;self.failures=[]

    def checkpoint(self,phase,event):
        if phase not in self.PHASES or event not in ('start','completed','failed'):raise ValueError('Unknown diagnostic phase or event')
        if len(self.events)>=32:raise RuntimeError('Diagnostic phase event limit exceeded')
        current={'sequence':len(self.events)+1,'phase':phase,'event':event,
                 'elapsedSeconds':round(time.monotonic()-self.started,3)}
        self.events.append(current)
        state={'schemaVersion':1,'diagnosticOnly':True,'group':self.group,'current':current,
               'events':self.events,'failedPhases':[x['phase'] for x in self.events if x['event']=='failed'],
               'checkpointFailures':self.failures}
        temporary=self.report/'phase-checkpoint.tmp'
        try:
            with temporary.open('w',encoding='utf-8') as stream:
                json.dump(state,stream,indent=2);stream.write('\n');stream.flush();os.fsync(stream.fileno())
            os.replace(temporary,self.report/'phase-checkpoint.json')
        except Exception as failure:
            # Diagnostics must never prevent the existing temporary-directory exit from running.
            self.failed_checkpoint(phase,event,'write',failure);event='checkpoint-failed'
        try:print('[method-refactoring] phase='+phase+' group='+self.group+' event='+event,flush=True)
        except Exception as failure:self.failed_checkpoint(phase,event,'console',failure)

    def failed_checkpoint(self,phase,event,kind,failure):
        if self.failure is None:self.failure=failure
        if len(self.failures)<32:self.failures.append({'phase':phase,'event':event,'kind':kind})

    def require_recorded(self):
        if self.failure is not None:
            raise RuntimeError('A diagnostic checkpoint could not be recorded; verification cannot report success.') from self.failure

    @contextmanager
    def phase(self,name):
        self.checkpoint(name,'start')
        try:yield
        except BaseException:
            self.checkpoint(name,'failed');raise
        else:self.checkpoint(name,'completed')


class PhasedTemporaryDirectory:
    """Observe the existing context-manager entry/exit without changing its cleanup policy."""
    def __init__(self,recorder):self.recorder=recorder;self.delegate=None
    def __enter__(self):
        with self.recorder.phase('temp-allocation'):
            self.delegate=tempfile.TemporaryDirectory(prefix='goforge-method-verification-')
            return self.delegate.__enter__()
    def __exit__(self,kind,value,traceback):
        with self.recorder.phase('temp-cleanup'):
            return self.delegate.__exit__(kind,value,traceback)


def verify(app,report,source_root=ROOT,development_overlay=None,*,group="method-add",strict_sources=False):
    report=Path(report).absolute();report.mkdir(parents=True,exist_ok=False)
    summary={'passed':False,'packagedFeaturePassed':False,'developmentOverlayOnly':development_overlay is not None,
             'scope':'Real packaged Go method-refactoring protocol; no IDE GUI or full GoLand parity claim.'}
    try:
        name,ready,expected=CASE_GROUPS[group]
        main='dev.goforge.refactor.'+name
        recorder=PhaseRecorder(report,group)
        with recorder.phase('layout'):
            layout=package_layout(app);source_root=Path(source_root).resolve(strict=True);source=source_root/'src/test/java/dev/goforge/refactor'/(name+'.java')
            summary.update(group=group,expectedCaseIds=[main+'#'+case for case in expected])
            if not source.is_file():raise RuntimeError('Repository method probe source is missing')
            overlay=Path(development_overlay).resolve(strict=True) if development_overlay else None
            if overlay is not None and not overlay.is_dir():raise RuntimeError('Development overlay must be an explicit classes directory')
            if overlay is None:
                with zipfile.ZipFile(layout['plugin']) as archive:
                    if 'dev/goforge/refactor/GoMethodParameterSafety.class' not in archive.namelist():raise RuntimeError('This package does not contain the new typed method implementation')
                    if group=='free-function-reorder':
                        for implementation in ('GoFunctionReorder','GoFunctionReorderSource','GoFunctionReorderSafety','GoFunctionReorderSafetySource'):
                            if 'dev/goforge/refactor/'+implementation+'.class' not in archive.namelist():raise RuntimeError('This package does not contain the static-function reorder implementation')
        with recorder.phase('hash'):
            summary.update(source={'path':str(source),'sha256':sha256(source)},tools={k:{'path':str(layout[k]),'sha256':sha256(layout[k])} for k in ('java','javac','go','gopls')},
                           classpathJars=[{'path':str(p),'sha256':sha256(p)} for p in layout['jars']],overlay=str(overlay) if overlay else None)
            if 'jnaDispatch' in layout:
                summary['nativeInputs']={k:{'path':str(layout[k]),'sha256':sha256(layout[k])} for k in ('jnaDispatch','jnaProvider','jbrRelease')}
            classes=report/'classes';classes.mkdir();classpath=os.pathsep.join([str(classes)]+([str(overlay)] if overlay else [])+[str(p) for p in layout['jars']])
        with PhasedTemporaryDirectory(recorder) as temporary:
            owned=Path(temporary).resolve(strict=True);(owned/'tmp').mkdir();env=probe_environment(layout,owned,os.environ)
            with recorder.phase('javac'):
                compile_sources=[str(source)]
                if strict_sources:
                    bootstrap=owned/(NATIVE_BOOTSTRAP+'.java');bootstrap.write_text(NATIVE_SOURCE,encoding='utf-8');compile_sources.append(str(bootstrap))
                    summary['nativeBootstrapSha256']=sha256(bootstrap)
                summary['compile']=run_java_tool(layout['javac'],['--release','21','-encoding','UTF-8','-implicit:none','-sourcepath','','-cp',classpath,'-d',str(classes),*compile_sources],report,'compile',env,source_root,120)
                compiled=sorted(p.relative_to(classes).as_posix() for p in classes.rglob('*.class'))
                prefix='dev/goforge/refactor/'+name
                if not compiled or any(not (x==prefix+'.class' or x.startswith(prefix+'$') or (strict_sources and x==NATIVE_BOOTSTRAP+'.class')) for x in compiled):raise RuntimeError('Probe compilation emitted unexpected classes')
                summary['compiledProbeClasses']={x:sha256(classes/x) for x in compiled}
            entry=[NATIVE_BOOTSTRAP,main,str(report/'protocol'),str(layout['jnaDispatch']),str(layout['jnaProvider']),str(report/'native-binding.json')] if strict_sources else [main,str(report/'protocol')]
            args=['-Djava.io.tmpdir='+str(owned/'tmp'),'-Dgoforge.probe.go='+str(layout['go']),'-Dgoforge.probe.gopls='+str(layout['gopls']),'-Dgoforge.probe.cacheRoot='+str(owned),*(native_arguments(layout) if 'jnaDispatch' in layout else []),'-cp',classpath,*entry]
            with recorder.phase('jvm'):
                summary['runtime']=run_java_tool(layout['java'],args,report,'probe',env,source_root,1200)
            with recorder.phase('result-validation'):
                if strict_sources:summary['nativeBinding']=require_native_result(json.loads((report/'native-binding.json').read_text(encoding='utf-8')),layout)
                result=json.loads((report/'protocol/result.json').read_text(encoding='utf-8'))
                preservation=('originalSourceBytesUnchanged' if group=='free-scalar' else 'originalSourceBytesUnchangedOrRestoredAfterExplicitFixtureTest') if strict_sources else None
                validated=require_results(result,layout,overlay,expected_cases=expected,require_ready=ready,source_preservation=preservation)
                summary['actualCaseIds']=[main+'#'+item['case'] for item in result['checks']]
                if group=='free-function-reorder':summary['behaviorComparisons']=require_reorder_behavior(result)
                if group=='method-result':summary['resultTypeEvidence']=require_method_result_behavior(result)
        recorder.require_recorded()
        summary.update(validated);summary['passed']=True
    except Exception as failure:
        summary['passed']=False;summary['packagedFeaturePassed']=False
        summary['failure']=type(failure).__name__+': '+str(failure)
    finally:
        (report/'verification.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    return summary


def verify_suite(app,report,source_root=ROOT,development_overlay=None):
    """Default CI entry: all 141 cases must pass; the original verify() API stays at 23."""
    report=Path(report).absolute();report.mkdir(parents=True,exist_ok=False)
    summary={'passed':False,'packagedFeaturePassed':False,'developmentOverlayOnly':development_overlay is not None,
             'schemaVersion':3,'scope':'141 packaged method/scalar/reorder/result protocol cases; no IDE GUI or complete parity claim.','groups':{},
             'expectedCaseIds':list(SUITE_CASE_IDS)}
    try:
        configured=tuple('dev.goforge.refactor.'+name+'#'+case for name,_,cases in CASE_GROUPS.values() for case in cases)
        if tuple(CASE_GROUPS)!=SUITE_GROUPS or configured!=SUITE_CASE_IDS or len(set(configured))!=141:
            raise RuntimeError('Expected the fixed five groups and 141 unique class-qualified cases')
        actual=[]
        for group in SUITE_GROUPS:
            name,_,expected=CASE_GROUPS[group];started=time.monotonic();group_passed=False
            group_progress('start',group,len(expected),len(actual),started)
            try:
                result=verify(app,report/group,source_root,development_overlay,group=group,strict_sources=True)
                summary['groups'][group]=result
                if result.get('passed') is not True or 'failure' in result:raise RuntimeError(group+' failed: '+str(result.get('failure','no successful report')))
                if result.get('developmentOverlayOnly') is not (development_overlay is not None) or result.get('packagedFeaturePassed') is not (development_overlay is None):
                    raise RuntimeError(group+' returned inconsistent package/overlay identity')
                ids=result.get('actualCaseIds');wanted={'dev.goforge.refactor.'+name+'#'+case for case in expected}
                if result.get('cases')!=len(expected) or not isinstance(ids,list) or len(ids)!=len(wanted) or set(ids)!=wanted:
                    raise RuntimeError(group+' did not return its exact verified case IDs')
                if group=='free-function-reorder':
                    if result.get('behaviorComparisons')!=REORDER_BEHAVIOR:raise RuntimeError('Reorder group lost its fixed behavior comparisons')
                    summary['behaviorComparisons']=dict(result['behaviorComparisons'])
                if group=='method-result':
                    evidence=result.get('resultTypeEvidence')
                    if not isinstance(evidence,dict) or evidence.get('comparisons')!=METHOD_RESULT_BEHAVIOR or any(type(evidence.get(key)) is not int or evidence[key]<0 for key in ('serverMessageCount','orphanedFileWarningCount')) or evidence['orphanedFileWarningCount']>evidence['serverMessageCount']:
                        raise RuntimeError('Method result group lost its fixed runtime evidence or warning accounting')
                    summary['resultTypeEvidence']=evidence
                actual.extend(ids);group_passed=True
            finally:
                group_progress('end',group,len(expected),len(actual),started,'passed' if group_passed else 'failed')
        if len(actual)!=141 or len(set(actual))!=141:raise RuntimeError('Expected 141 unique class-qualified cases')
        summary.update(passed=True,packagedFeaturePassed=development_overlay is None,cases=141,actualCaseIds=actual)
    except Exception as failure:
        summary.update(passed=False,packagedFeaturePassed=False,failure=type(failure).__name__+': '+str(failure))
    finally:
        (report/'verification.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    return summary


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app',type=Path,required=True);parser.add_argument('--report-dir',type=Path,required=True)
    parser.add_argument('--source-root',type=Path,default=ROOT)
    parser.add_argument('--development-overlay',type=Path,help='Local control only: never reports packaged-feature acceptance')
    parser.add_argument('--legacy-method-only',action='store_true',help='Run only the original 23-case method Add Parameter check')
    args=parser.parse_args()
    try:
        result=(verify if args.legacy_method_only else verify_suite)(args.app,args.report_dir,args.source_root,args.development_overlay)
        print(json.dumps({key:result.get(key) for key in ('passed','packagedFeaturePassed','developmentOverlayOnly','cases','failure')},ensure_ascii=False))
        raise SystemExit(0 if result['passed'] else 1)
    except Exception as error:
        print(type(error).__name__+': '+str(error),file=sys.stderr);raise SystemExit(1)
