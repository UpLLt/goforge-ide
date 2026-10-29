#!/usr/bin/env python3
"""Opt-in real method-refactoring check using only a selected GoForge package and owned fixtures.

A development class overlay is explicitly reported as a control run, never package acceptance.
No IDE GUI or complete GoLand equivalence is established by this protocol check.
"""
from __future__ import annotations
import argparse
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
    'method-scalar': ('GoMethodScalarTypeProtocolProbe', True, ('value-imports', 'pointer-imports', 'parameter-incompatible-caller', 'parameter-incompatible-body', 'shadow-old-refused', 'shadow-new-refused', 'alias-shadow-refused', 'method-result-refused', 'dirty-unicode', 'init-and-testmain-not-run', 'interface-old-refused', 'interface-new-refused', 'interface-alias-refused', 'constraint-refused', 'anonymous-interface-refused', 'method-value-refused', 'method-expression-refused', 'promoted-refused', 'public-method-refused', 'public-receiver-refused', 'generic-receiver-refused', 'alias-receiver-refused', 'missing-reference-refused', 'outside-reference-refused', 'metadata-change-after-preview', 'goenv-change-after-preview', 'sdk-change-after-preview', 'default-cgo-parameter', 'cancelled-after-references')),
    'free-scalar': ('GoChangeScalarTypeProtocolProbe', True, ('goenv-change-after-preview', 'sdk-change-after-preview', 'default-cgo-parameter', 'parameter-default-import', 'alias-shadow-refused', 'parameter-constant', 'result-inferred', 'named-result', 'generic-parameter', 'generic-result', 'dirty-unicode', 'init-and-testmain-not-run', 'parameter-incompatible-caller', 'result-incompatible-consumer', 'result-incompatible-body', 'function-value-refused', 'missing-reference-refused', 'outside-reference-refused', 'metadata-change-after-preview', 'cancelled-after-references')),
}

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


def verify(app,report,source_root=ROOT,development_overlay=None,*,group="method-add",strict_sources=False):
    report=Path(report).absolute();report.mkdir(parents=True,exist_ok=False)
    summary={'passed':False,'packagedFeaturePassed':False,'developmentOverlayOnly':development_overlay is not None,
             'scope':'Real packaged Go method-refactoring protocol; no IDE GUI or full GoLand parity claim.'}
    try:
        name,ready,expected=CASE_GROUPS[group]
        main='dev.goforge.refactor.'+name
        layout=package_layout(app);source_root=Path(source_root).resolve(strict=True);source=source_root/'src/test/java/dev/goforge/refactor'/(name+'.java')
        summary.update(group=group,expectedCaseIds=[main+'#'+case for case in expected])
        if not source.is_file():raise RuntimeError('Repository method probe source is missing')
        overlay=Path(development_overlay).resolve(strict=True) if development_overlay else None
        if overlay is not None and not overlay.is_dir():raise RuntimeError('Development overlay must be an explicit classes directory')
        if overlay is None:
            with zipfile.ZipFile(layout['plugin']) as archive:
                if 'dev/goforge/refactor/GoMethodParameterSafety.class' not in archive.namelist():raise RuntimeError('This package does not contain the new typed method implementation')
        summary.update(source={'path':str(source),'sha256':sha256(source)},tools={k:{'path':str(layout[k]),'sha256':sha256(layout[k])} for k in ('java','javac','go','gopls')},
                       classpathJars=[{'path':str(p),'sha256':sha256(p)} for p in layout['jars']],overlay=str(overlay) if overlay else None)
        if 'jnaDispatch' in layout:
            summary['nativeInputs']={k:{'path':str(layout[k]),'sha256':sha256(layout[k])} for k in ('jnaDispatch','jnaProvider','jbrRelease')}
        classes=report/'classes';classes.mkdir();classpath=os.pathsep.join([str(classes)]+([str(overlay)] if overlay else [])+[str(p) for p in layout['jars']])
        with tempfile.TemporaryDirectory(prefix='goforge-method-verification-') as temporary:
            owned=Path(temporary).resolve(strict=True);(owned/'tmp').mkdir();env=probe_environment(layout,owned,os.environ)
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
            summary['runtime']=run_java_tool(layout['java'],args,report,'probe',env,source_root,1200)
            if strict_sources:summary['nativeBinding']=require_native_result(json.loads((report/'native-binding.json').read_text(encoding='utf-8')),layout)
            result=json.loads((report/'protocol/result.json').read_text(encoding='utf-8'))
            preservation=('originalSourceBytesUnchanged' if group=='free-scalar' else 'originalSourceBytesUnchangedOrRestoredAfterExplicitFixtureTest') if strict_sources else None
            validated=require_results(result,layout,overlay,expected_cases=expected,require_ready=ready,source_preservation=preservation)
            summary['actualCaseIds']=[main+'#'+item['case'] for item in result['checks']]
        summary.update(validated);summary['passed']=True
    except Exception as failure:
        summary['passed']=False;summary['packagedFeaturePassed']=False
        summary['failure']=type(failure).__name__+': '+str(failure)
    finally:
        (report/'verification.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    return summary


def verify_suite(app,report,source_root=ROOT,development_overlay=None):
    """Default CI entry: all 72 cases must pass; per-group legacy reports remain intact."""
    report=Path(report).absolute();report.mkdir(parents=True,exist_ok=False)
    summary={'passed':False,'packagedFeaturePassed':False,'developmentOverlayOnly':development_overlay is not None,
             'schemaVersion':2,'scope':'72 packaged method/scalar protocol cases; no IDE GUI or complete parity claim.','groups':{},
             'expectedCaseIds':['dev.goforge.refactor.'+name+'#'+case for name,_,cases in CASE_GROUPS.values() for case in cases]}
    try:
        actual=[]
        for group in CASE_GROUPS:
            result=verify(app,report/group,source_root,development_overlay,group=group,strict_sources=True)
            summary['groups'][group]=result
            if result.get('passed') is not True:raise RuntimeError(group+' failed: '+str(result.get('failure','no successful report')))
            if result.get('developmentOverlayOnly') is not (development_overlay is not None) or result.get('packagedFeaturePassed') is not (development_overlay is None):
                raise RuntimeError(group+' returned inconsistent package/overlay identity')
            name,_,expected=CASE_GROUPS[group];ids=result.get('actualCaseIds')
            wanted={'dev.goforge.refactor.'+name+'#'+case for case in expected}
            if not isinstance(ids,list) or len(ids)!=len(wanted) or set(ids)!=wanted:raise RuntimeError(group+' did not return its exact verified case IDs')
            actual.extend(ids)
        if len(actual)!=72 or len(set(actual))!=72:raise RuntimeError('Expected 72 unique class-qualified cases')
        summary.update(passed=True,packagedFeaturePassed=development_overlay is None,cases=72,actualCaseIds=actual)
    except Exception as failure:
        summary['failure']=type(failure).__name__+': '+str(failure)
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
