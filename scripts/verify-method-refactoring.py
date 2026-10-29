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
    return paths


def probe_environment(layout,owned,inherited):
    blocked={'JAVA_HOME','JDK_HOME','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH'}
    env={k:v for k,v in inherited.items() if not k.upper().startswith(('GO','IDEA_')) and k.upper() not in blocked}
    # Keep the host's normal non-Go system environment, but make every Go/Java temporary write owned.
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


def require_results(result,layout,overlay=None):
    checks=result.get('checks')
    if result.get('passed') is not True or not isinstance(checks,list):raise RuntimeError('Method probe did not report success')
    names=[x.get('case') for x in checks]
    if len(names)!=len(EXPECTED_CASES) or set(names)!=set(EXPECTED_CASES):raise RuntimeError('Method probe did not execute the exact 23 cases')
    for item in checks:
        if item.get('passed') is not True or item.get('versionedDiagnosticsReady') is not True or item.get('fixtureDeleted') is not True or item.get('goplsAliveAfterCleanup') is not False or item.get('goplsExit')!=0 or 'cleanupFailure' in item:
            raise RuntimeError('Method case failed readiness, execution or cleanup: '+str(item.get('case')))
    provider=Path(overlay).resolve(strict=True) if overlay else layout['plugin'].resolve()
    for key in ('plannerCodeSource','typedProofCodeSource','compilerProofCodeSource'):
        if uri_file(result.get(key,''))!=provider:raise RuntimeError('Method implementation was not loaded from the expected provider: '+key)
    if uri_file(result.get('lspModelCodeSource',''))!=layout['lsp']:raise RuntimeError('Probe did not use the packaged pinned LSP model')
    for key,expected in [('javaHome',layout['jbr']),('goExecutable',layout['go']),('goplsExecutable',layout['gopls'])]:
        if Path(result.get(key,'')).resolve(strict=True)!=expected.resolve():raise RuntimeError('Probe tool identity differs: '+key)
    return {'cases':len(checks),'packagedFeaturePassed':overlay is None,'developmentOverlayOnly':overlay is not None}


def verify(app,report,source_root=ROOT,development_overlay=None):
    report=Path(report).absolute();report.mkdir(parents=True,exist_ok=False)
    summary={'passed':False,'packagedFeaturePassed':False,'developmentOverlayOnly':development_overlay is not None,
             'scope':'Real packaged Go method-refactoring protocol; no IDE GUI or full GoLand parity claim.'}
    try:
        layout=package_layout(app);source_root=Path(source_root).resolve(strict=True);source=source_root/PROBE
        if not source.is_file():raise RuntimeError('Repository method probe source is missing')
        overlay=Path(development_overlay).resolve(strict=True) if development_overlay else None
        if overlay is not None and not overlay.is_dir():raise RuntimeError('Development overlay must be an explicit classes directory')
        if overlay is None:
            with zipfile.ZipFile(layout['plugin']) as archive:
                if 'dev/goforge/refactor/GoMethodParameterSafety.class' not in archive.namelist():raise RuntimeError('This package does not contain the new typed method implementation')
        summary.update(source={'path':str(source),'sha256':sha256(source)},tools={k:{'path':str(layout[k]),'sha256':sha256(layout[k])} for k in ('java','javac','go','gopls')},
                       classpathJars=[{'path':str(p),'sha256':sha256(p)} for p in layout['jars']],overlay=str(overlay) if overlay else None)
        classes=report/'classes';classes.mkdir();classpath=os.pathsep.join([str(classes)]+([str(overlay)] if overlay else [])+[str(p) for p in layout['jars']])
        with tempfile.TemporaryDirectory(prefix='goforge-method-verification-') as temporary:
            owned=Path(temporary).resolve(strict=True);(owned/'tmp').mkdir();env=probe_environment(layout,owned,os.environ)
            summary['compile']=run_java_tool(layout['javac'],['--release','21','-encoding','UTF-8','-implicit:none','-sourcepath','','-cp',classpath,'-d',str(classes),str(source)],report,'compile',env,source_root,120)
            compiled=sorted(p.relative_to(classes).as_posix() for p in classes.rglob('*.class'))
            if not compiled or any(not x.startswith('dev/goforge/refactor/GoMethodAddParameterProtocolProbe') for x in compiled):raise RuntimeError('Probe compilation emitted unexpected classes')
            summary['compiledProbeClasses']={x:sha256(classes/x) for x in compiled}
            args=['-Djava.io.tmpdir='+str(owned/'tmp'),'-Dgoforge.probe.go='+str(layout['go']),'-Dgoforge.probe.gopls='+str(layout['gopls']),'-Dgoforge.probe.cacheRoot='+str(owned),'-cp',classpath,MAIN,str(report/'protocol')]
            summary['runtime']=run_java_tool(layout['java'],args,report,'probe',env,source_root,1200)
            result=json.loads((report/'protocol/result.json').read_text(encoding='utf-8'))
            validated=require_results(result,layout,overlay)
        summary.update(validated);summary['passed']=True
    except Exception as failure:
        summary['passed']=False;summary['packagedFeaturePassed']=False
        summary['failure']=type(failure).__name__+': '+str(failure)
    finally:
        (report/'verification.json').write_text(json.dumps(summary,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    return summary


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app',type=Path,required=True);parser.add_argument('--report-dir',type=Path,required=True)
    parser.add_argument('--source-root',type=Path,default=ROOT)
    parser.add_argument('--development-overlay',type=Path,help='Local control only: never reports packaged-feature acceptance')
    args=parser.parse_args()
    try:
        result=verify(args.app,args.report_dir,args.source_root,args.development_overlay)
        print(json.dumps({key:result.get(key) for key in ('passed','packagedFeaturePassed','developmentOverlayOnly','cases','failure')},ensure_ascii=False))
        raise SystemExit(0 if result['passed'] else 1)
    except Exception as error:
        print(type(error).__name__+': '+str(error),file=sys.stderr);raise SystemExit(1)
