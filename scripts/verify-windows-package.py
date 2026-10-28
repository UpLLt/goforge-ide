#!/usr/bin/env python3
"""Windows-native package checks. Runs only generated fixtures, never user projects.

This verifies the native launcher and actual bundled Go/gopls/Delve engines. It
does not turn headless startup or debugger protocol checks into IDE UI acceptance.
"""
from __future__ import annotations
import argparse
import ctypes
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
from urllib.parse import unquote

ROOT=Path(__file__).resolve().parents[1]


def load_protocol():
    spec=importlib.util.spec_from_file_location('goforge_windows_wire',ROOT/'scripts/verify-protocols.py')
    p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
    # Cold Windows Go builds can exceed the small development-probe timeout.
    base=p.FramedJson
    class WindowsFramedJson(base):
        def receive_until(self,predicate,timeout=120):
            return super().receive_until(predicate,timeout)
    p.FramedJson=WindowsFramedJson
    return p


def command(args, **kwargs):
    p=subprocess.run([str(x) for x in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                     text=True,encoding='utf-8',errors='replace',timeout=kwargs.pop('timeout',120),**kwargs)
    if p.returncode: raise RuntimeError(f'{Path(args[0]).name} returned {p.returncode}: {p.stdout[-5000:]}')
    return p.stdout


def terminate_owned(p):
    if p and p.poll() is None:
        subprocess.run(['taskkill','/PID',str(p.pid),'/T','/F'],capture_output=True,timeout=15)
        p.wait(timeout=15)


def pid_handle(pid):
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.restype=ctypes.c_void_p
    handle=kernel.OpenProcess(0x00100000|0x1000,False,pid)
    if not handle: raise RuntimeError('Cannot observe generated debuggee process '+str(pid))
    return handle


def require_exited(handle):
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.WaitForSingleObject.argtypes=[ctypes.c_void_p,ctypes.c_uint32]
    kernel.CloseHandle.argtypes=[ctypes.c_void_p]
    try:
        if kernel.WaitForSingleObject(handle,10000)!=0: raise RuntimeError('Generated debuggee did not exit')
    finally:kernel.CloseHandle(handle)


def debug(wire, app, project, env, out, cancel=False):
    log=out/('delve-cancel.log' if cancel else 'delve-full.log')
    process=conn=None; target=None; reader=writer=None
    with log.open('w',encoding='utf-8') as stream:
        try:
            process=subprocess.Popen([str(app/'goforge-tools/bin/dlv.exe'),'dap','--listen=127.0.0.1:0'],
                                     cwd=project,env=env,stdout=stream,stderr=stream)
            deadline=time.monotonic()+20; match=None
            while time.monotonic()<deadline:
                match=re.search(r'DAP server listening at: (127\.0\.0\.1):(\d+)',log.read_text(encoding='utf-8'))
                if match:break
                if process.poll() is not None:raise RuntimeError('Delve exited before ready: '+log.read_text(encoding='utf-8'))
                time.sleep(.05)
            if not match:raise TimeoutError('Delve listener')
            conn=socket.create_connection((match[1],int(match[2])),timeout=10);conn.settimeout(None)
            reader,writer=conn.makefile('rb'),conn.makefile('wb');rpc=wire.FramedJson(reader,writer)
            rpc.dap('initialize',{'clientID':'goforge-windows-native','adapterID':'go','pathFormat':'path',
                                 'linesStartAt1':True,'columnsStartAt1':True})
            rpc.dap('launch',{'request':'launch','mode':'debug','program':str(project),'cwd':str(project)})
            rpc.receive_until(lambda m:m.get('event')=='initialized')
            bp=rpc.dap('setBreakpoints',{'source':{'path':str(project/'main.go')},'breakpoints':[{'line':5}]})
            assert bp['breakpoints'][0]['verified'],bp
            rpc.dap('configurationDone')
            stopped=rpc.receive_until(lambda m:m.get('event')=='stopped')
            assert stopped['body']['reason']=='breakpoint',stopped
            event=rpc.receive_until(lambda m:m.get('event')=='process')
            target=pid_handle(event['body']['systemProcessId'])
            thread=stopped['body']['threadId']
            frames=rpc.dap('stackTrace',{'threadId':thread})['stackFrames'];frame=frames[0]
            assert frame['line']==5,frame
            scopes=rpc.dap('scopes',{'frameId':frame['id']})['scopes'];variables=[]
            for scope in scopes:
                if not scope.get('expensive'):
                    variables+=rpc.dap('variables',{'variablesReference':scope['variablesReference']})['variables']
            assert any(v['name']=='message' and 'Windows native' in v['value'] for v in variables),variables
            watch=rpc.dap('evaluate',{'expression':'message','frameId':frame['id'],'context':'watch'})
            assert 'Windows native' in watch['result'],watch
            if cancel:
                rpc.dap('disconnect',{'terminateDebuggee':True})
            else:
                rpc.dap('next',{'threadId':thread})
                rpc.receive_until(lambda m:m.get('event')=='stopped' and m.get('body',{}).get('reason')=='step')
                rpc.dap('continue',{'threadId':thread})
                exited=rpc.receive_until(lambda m:m.get('event')=='exited')
                assert exited['body']['exitCode']==0,exited
                rpc.receive_until(lambda m:m.get('event')=='terminated')
                rpc.dap('disconnect',{'terminateDebuggee':False})
            process.wait(timeout=15);observed=target;target=None;require_exited(observed)
            return {'passed':True,'adapterExitCode':process.returncode,'targetExited':True,
                    'scope':'launch/breakpoint/locals/evaluate/'+('cancel cleanup' if cancel else 'step/continue/exit cleanup'),
                    'protocol':rpc.dap_diagnostics()}
        finally:
            if writer:writer.close()
            if reader:reader.close()
            if conn:conn.close()
            terminate_owned(process)
            if target:require_exited(target)


def lsp(wire,app,project,env,out):
    with (out/'gopls.log').open('w',encoding='utf-8') as log:
        p=subprocess.Popen([str(app/'goforge-tools/bin/gopls.exe'),'serve'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,
                           stderr=log,cwd=project,env=env)
        try:
            rpc=wire.FramedJson(p.stdout,p.stdin)
            rpc.lsp('initialize',{'processId':os.getpid(),'rootUri':project.as_uri(),'capabilities':{},
                                 'workspaceFolders':[{'uri':project.as_uri(),'name':'Windows native'}]})
            rpc.notify('initialized',{})
            source=project/'main.go';text=source.read_text(encoding='utf-8')
            rpc.notify('textDocument/didOpen',{'textDocument':{'uri':source.as_uri(),'languageId':'go','version':1,'text':text}})
            result=rpc.lsp('textDocument/definition',{'textDocument':{'uri':source.as_uri()},'position':{'line':3,'character':14}})
            assert isinstance(result,list) and result,result
            location=result[0]
            assert unquote(location['uri']).casefold()==unquote((project/'hello.go').as_uri()).casefold(),result
            assert location['range']['start']=={'line':1,'character':5},result
            rpc.lsp('shutdown');rpc.notify('exit');p.wait(timeout=15)
            assert p.returncode==0,p.returncode
            return {'passed':True,'definitionResponse':result,'exitCode':p.returncode}
        finally:terminate_owned(p)


def main(app,report):
    if os.name!='nt':raise RuntimeError('Windows native validation requires Windows; cross-platform runs are rejected')
    app=app.resolve();report.mkdir(parents=True,exist_ok=True)
    build_inputs=app/'goforge-build-inputs.json'
    identity=json.loads(build_inputs.read_text(encoding='utf-8'))
    result={'passed':False,'scope':'Native launcher and bundled engines; IDE GUI feature validation remains separate',
            'app':str(app),'checks':{},'pluginZipSha256':identity['pluginZipSha256'],
            'buildInputsSha256':hashlib.sha256(build_inputs.read_bytes()).hexdigest(),
            'launcherSha256':hashlib.sha256((app/'bin/goforge64.exe').read_bytes()).hexdigest()}
    try:
        wire=load_protocol()
        with tempfile.TemporaryDirectory(prefix='GoForge relocation 中文 ') as temporary:
            temp=Path(temporary);relocated=temp/'GoForge relocated';shutil.copytree(app,relocated)
            env={k.upper():v for k,v in os.environ.items() if k.upper() not in {'GOROOT','GOPATH','GOBIN','GOFLAGS','GOFORGE_JDK','GOFORGE_VM_OPTIONS','GOFORGE_PROPERTIES','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'}}
            env.update(APPDATA=str(temp/'Roaming'),LOCALAPPDATA=str(temp/'Local'),GOENV='off',GOTOOLCHAIN='local',CGO_ENABLED='0',
                       GOPATH=str(temp/'gopath'),GOCACHE=str(temp/'gocache'),GOTELEMETRY='off',
                       PATH=os.pathsep.join([str(relocated/'goforge-tools/go/bin'),str(relocated/'goforge-tools/bin'),str(Path(env['SYSTEMROOT'])/'System32')]))
            for name in ('APPDATA','LOCALAPPDATA'):Path(env[name]).mkdir()
            powershell=Path(env['SYSTEMROOT'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
            resources=json.loads(command([powershell,'-NoProfile','-NonInteractive','-Command',
                '$v=(Get-Item -LiteralPath $env:GOFORGE_VERIFY_EXE).VersionInfo; '
                '$s=Get-AuthenticodeSignature -LiteralPath $env:GOFORGE_VERIFY_EXE; '
                '@{product=$v.ProductName; description=$v.FileDescription; original=$v.OriginalFilename; signature=$s.Status.ToString()} | ConvertTo-Json -Compress'],
                env={**env,'GOFORGE_VERIFY_EXE':str(relocated/'bin/goforge64.exe')}))
            assert resources=={'product':'GoForge','description':'GoForge','original':'goforge64.exe','signature':'NotSigned'},resources
            result['checks']['windowsVersionAndSignature']={'passed':True,'properties':resources}
            probe=command([relocated/'bin/goforge64.exe','goforge-package-info'],env=env,timeout=120)
            assert 'product=GoForge' in probe and str(relocated).lower() in probe.lower(),probe
            for key in ('config','system','plugins','log'):
                line=next(x for x in probe.splitlines() if x.startswith(key+'='));assert str(temp).lower() in line.lower(),line
            result['checks']['nativeLauncherRelocation']={'passed':True,'output':probe}
            go=relocated/'goforge-tools/go/bin/go.exe'
            assert Path(command([go,'env','GOROOT'],env=env).strip()).resolve()==(relocated/'goforge-tools/go').resolve()
            versions={name:command([relocated/path,*args],env=env) for name,path,args in [
                ('go','goforge-tools/go/bin/go.exe',['version']),('gopls','goforge-tools/bin/gopls.exe',['version']),
                ('dlv','goforge-tools/bin/dlv.exe',['version']),('node','goforge-tools/bin/node.exe',['--version']),
                ('devcontainers','goforge-tools/bin/node.exe',[str(relocated/'goforge-tools/devcontainer-cli/dist/spec-node/devContainersSpecCLI.js'),'--version'])]}
            for name,needle in {'go':'go1.26.4','gopls':'v0.23.0','dlv':'1.27.2','node':'v24.22.1','devcontainers':'0.89.0'}.items():assert needle in versions[name],versions
            result['checks']['versions']={'passed':True,'outputs':versions}
            project=temp/'fixture space 中文';project.mkdir()
            (project/'go.mod').write_text('module example.com/windows-native\n\ngo 1.26\n',encoding='utf-8')
            (project/'main.go').write_text('package main\nimport "fmt"\nfunc main() {\n message := Hello()\n fmt.Println(message)\n fmt.Println("done")\n}\n',encoding='utf-8')
            (project/'hello.go').write_text('package main\nfunc Hello() string { return "Windows native" }\n',encoding='utf-8')
            (project/'hello_test.go').write_text('package main\nimport "testing"\nfunc TestHello(t *testing.T) { if Hello() != "Windows native" { t.Fatal(Hello()) } }\n',encoding='utf-8')
            result['checks']['test']={'passed':True,'output':command([go,'test','-count=1','./...'],cwd=project,env=env)}
            output=command([go,'run','.'],cwd=project,env=env);assert 'Windows native' in output
            result['checks']['run']={'passed':True,'output':output}
            result['checks']['gopls']=lsp(wire,relocated,project,env,report)
            result['checks']['debug']=debug(wire,relocated,project,env,report)
            result['checks']['debugCancel']=debug(wire,relocated,project,env,report,cancel=True)
            result['passed']=True
    except BaseException as error:
        result['error']=type(error).__name__+': '+str(error);raise
    finally:
        (report/'native-verification.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
        print(json.dumps(result,indent=2,ensure_ascii=True))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app',type=Path,default=ROOT/'build/windows/GoForge')
    parser.add_argument('--report-dir',type=Path,default=ROOT/'.artifacts/windows-native')
    args=parser.parse_args();main(args.app,args.report_dir)
