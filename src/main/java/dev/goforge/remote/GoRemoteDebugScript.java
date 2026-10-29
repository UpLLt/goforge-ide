package dev.goforge.remote;

/** Transient stdio/loopback supervisor; never installed on the SSH host. */
public final class GoRemoteDebugScript {
    private GoRemoteDebugScript() {}
    /** A bounded first stdin line carries the helper, keeping SSH argv small on Windows. */
    public static String command() { return GoRemoteScripts.python(LOADER); }
    public static String payload() {
        var bytes=new java.io.ByteArrayOutputStream();
        try(var compressed=new java.util.zip.DeflaterOutputStream(bytes)) {
            compressed.write(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(java.io.IOException impossible){throw new AssertionError(impossible);}
        String encoded=java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
        if(encoded.length()>131072)throw new IllegalStateException("Remote debug helper exceeds its transport bound.");
        return encoded;
    }
    static final String LOADER="""
import sys,base64,zlib
line=sys.stdin.buffer.readline(131074)
if len(line)>131073 or not line.endswith(b'\\n') or b'\\r' in line:raise ValueError('Invalid debug helper envelope')
encoded=line[:-1]
compressed=base64.b64decode(encoded,validate=True)
if not encoded or base64.b64encode(compressed)!=encoded:raise ValueError('Noncanonical debug helper encoding')
reader=zlib.decompressobj();body=reader.decompress(compressed,524289)
if len(body)>524288 or not reader.eof or reader.unused_data or reader.unconsumed_tail:raise ValueError('Invalid or oversized debug helper stream')
exec(body.decode('utf-8','strict'))
""";
    public static final String SOURCE="""
import sys,os,json,hashlib,stat,subprocess,socket,threading,queue,re,time,signal,shutil,tempfile,select,base64
preamble=sys.stdin.buffer.readline(65537)
if len(preamble)>65536: raise ValueError('Startup manifest too large')
plan=json.loads(preamble); root=os.path.realpath(plan['root']); attached=plan['mode']=='attach'
stop=threading.Event(); write_lock=threading.Lock(); socket_lock=threading.Lock(); child=None; conn=None; private=None; failure=None
# This helper owns only its Delve server and private build directory. Attached PIDs never enter a kill group.
def cancelled(sig,frame): stop.set()
for sig in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP): signal.signal(sig,cancelled)
def send(message):
    data=json.dumps(message,separators=(',',':'),ensure_ascii=False).encode('utf-8')
    packet=('Content-Length: %d\\r\\n\\r\\n'%len(data)).encode()+data
    with write_lock:
        deadline=time.monotonic()+5;offset=0
        while offset<len(packet):
            if stop.is_set(): raise BrokenPipeError('DAP client stopped reading')
            remaining=deadline-time.monotonic()
            if remaining<=0: raise TimeoutError('DAP client output stalled for 5 seconds')
            try: offset+=os.write(1,packet[offset:])
            except BlockingIOError: select.select([],[1],[],min(.1,remaining))
def read_frame(stream):
    size=None; header=0
    while True:
        line=stream.readline(8193);header+=len(line)
        if not line: return None
        if header>8192: raise ValueError('DAP header exceeds 8 KiB')
        if line in (b'\\r\\n',b'\\n'): break
        key,sep,value=line.partition(b':')
        if key.lower()==b'content-length':
            if size is not None: raise ValueError('Duplicate DAP length')
            size=int(value.strip())
    if size is None or size<0 or size>16*1024*1024: raise ValueError('Invalid DAP length')
    data=bytearray()
    while len(data)<size:
        part=stream.read(size-len(data))
        if not part: raise ValueError('Truncated DAP body')
        data.extend(part)
    value=json.loads(data)
    if not isinstance(value,dict): raise ValueError('DAP message must be an object')
    return value
def remote_send(message,timeout=5):
    data=json.dumps(message,separators=(',',':'),ensure_ascii=False).encode()
    packet=('Content-Length: %d\\r\\n\\r\\n'%len(data)).encode()+data
    deadline=time.monotonic()+timeout
    if not socket_lock.acquire(timeout=timeout): raise TimeoutError('DAP adapter writer is busy')
    try:
        offset=0
        while offset<len(packet):
            if stop.is_set() and message.get('command')!='disconnect': raise InterruptedError('DAP transfer cancelled')
            remaining=deadline-time.monotonic()
            if remaining<=0: raise TimeoutError('DAP adapter stopped reading')
            try:
                sent=conn.send(packet[offset:],socket.MSG_DONTWAIT)
                if not sent: raise BrokenPipeError('DAP socket closed')
                offset+=sent
            except BlockingIOError: select.select([],[conn],[],min(.1,remaining))
    finally: socket_lock.release()
""" + GoRemoteDebugPaths.SOURCE + GoRemoteDebugSdkScript.SOURCE + GoRemoteDebugModuleScript.SOURCE + """
def sources(value):
    if isinstance(value,dict):
        for key,item in list(value.items()):
            if key=='source': source_path(item)
            elif key=='sources' and isinstance(item,list):
                for source in item: source_path(source)
            sources(item)
    elif isinstance(value,list):
        for item in value: sources(item)
def response(request,message):
    send({'seq':0,'type':'response','request_seq':request.get('seq',0),'command':request.get('command',''),'success':False,'message':message})
# Metadata parsing uses Go's read-only JSON commands; only the private workfile is edited.
def go_edit(kind,args):
    helper=subprocess.Popen([go,kind,'edit']+args,cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    deadline=time.monotonic()+15
    try:
        while True:
            if stop.is_set(): raise InterruptedError('Cancelled while checking workspace metadata')
            if time.monotonic()>=deadline: raise TimeoutError('Go workspace metadata check exceeded 15 seconds')
            try: out,err=helper.communicate(timeout=.1);break
            except subprocess.TimeoutExpired: pass
        if len(out)>1024*1024 or len(err)>1024*1024: raise ValueError('Go workspace metadata exceeds 1 MiB')
        if helper.returncode: raise ValueError('Go workspace metadata check failed: '+err.decode('utf-8','replace')[:2048])
        return out
    finally:
        if helper.poll() is None:
            helper.terminate()
            try: helper.wait(timeout=1)
            except subprocess.TimeoutExpired: helper.kill();helper.wait(timeout=1)
def workspace_directory(base,value):
    if not isinstance(value,str) or not value or value.startswith('/') or chr(92) in value or re.match(r'^[A-Za-z]:',value) or any(ord(c)<32 for c in value):
        raise ValueError('Workspace use/replace must be a portable relative path')
    path=os.path.normpath(os.path.join(base,value))
    if path!=root and not path.startswith(root+'/'): raise ValueError('Workspace use/replace leaves the verified root')
    if os.path.realpath(path)!=path: raise ValueError('Workspace directory contains a symlink')
    relative=os.path.relpath(path,root)
    mod='go.mod' if relative=='.' else relative+'/go.mod'
    if mod not in plan['files']: raise ValueError('Workspace module is outside the reviewed manifest: '+relative)
    return relative
def prepare_workspace():
    binding=plan.get('workspace',{})
    if not binding.get('workFile'): return
    if binding['workFile']!='go.work' or 'go.work' not in plan['files']: raise ValueError('Unreviewed workspace file')
    work=json.loads(go_edit('work',['-json',os.path.join(root,'go.work')]))
    modules=sorted(workspace_directory(root,item['DiskPath']) for item in (work.get('Use') or []))
    if set(modules)!=set(binding['modules']) or len(modules)!=len(binding['modules']) or not 1<=len(modules)<=32: raise ValueError('Workspace modules differ from the reviewed binding')
    replacements=set()
    def inspect_replacements(base,metadata):
        for item in metadata.get('Replace') or []:
            if not item['New'].get('Version'): replacements.add(workspace_directory(base,item['New']['Path']))
    inspect_replacements(root,work)
    for module in modules:
        base=os.path.join(root,module)
        inspect_replacements(base,json.loads(go_edit('mod',['-json',os.path.join(base,'go.mod')])))
    if replacements!=set(binding['localReplacements']) or len(replacements)!=len(binding['localReplacements']): raise ValueError('Workspace replacements differ from the reviewed binding')
    # Workspace readonly builds can write go.work.sum. Relocate only the workfile/sum into our private directory.
    copy=os.path.join(private,'go.work');shutil.copyfile(os.path.join(root,'go.work'),copy)
    if 'go.work.sum' in plan['files']: shutil.copyfile(os.path.join(root,'go.work.sum'),copy+'.sum')
    changes=[]
    for item in work.get('Use') or []:
        changes+=['-dropuse='+item['DiskPath'],'-use='+os.path.join(root,workspace_directory(root,item['DiskPath']))]
    for item in work.get('Replace') or []:
        if not item['New'].get('Version'):
            old=item['Old']['Path']+('@'+item['Old']['Version'] if item['Old'].get('Version') else '')
            changes+=['-replace='+old+'='+os.path.join(root,workspace_directory(root,item['New']['Path']))]
    go_edit('work',changes+[copy]);env['GOWORK']=copy
def input_loop():
    global failure
    try:
        while not stop.is_set():
            msg=read_frame(sys.stdin.buffer)
            if msg is None: break
            command=msg.get('command');args=msg.setdefault('arguments',{})
            debug_sdk_request(msg)
            if command=='source':
                debug_sdk_source(msg);continue
            if command in ('launch','attach'):
                if command!=('attach' if attached else 'launch'): response(msg,'Launch/attach mode does not match the reviewed configuration');continue
                msg['arguments']=dict(plan['arguments']);msg['arguments']['cwd']=root
                # The bridge maps both directions itself, independent of Delve host path conventions.
                msg['arguments']['substitutePath']=[]
                if not attached:
                    msg['arguments']['program']=os.path.join(root,plan['target'])
                    if plan['mode']!='exec': msg['arguments']['output']=os.path.join(private,'debug-target')
            elif command=='disconnect':
                args['terminateDebuggee']=not attached
            elif command=='terminate' and attached:
                response(msg,'This attached process is preserved. Use Disconnect to detach.');continue
            elif command in ('setBreakpoints','breakpointLocations'):
                suffix=local_relative(args.get('source',{}).get('path'))
                if suffix is None:
                    response(msg,'Breakpoint source is outside the verified workspace');continue
                args['source']['path']=os.path.join(root,*suffix.split('/'))
            remote_send(msg)
    except (BrokenPipeError,OSError,ValueError) as e: failure='DAP input closed: '+str(e)
    finally: stop.set()
def output_loop():
    global failure
    try:
        stream=conn.makefile('rb')
        while not stop.is_set():
            msg=read_frame(stream)
            if msg is None: break
            debug_sdk_output(msg);sources(msg);send(msg)
    except (BrokenPipeError,OSError,ValueError) as e: failure='DAP output closed: '+str(e)
    finally: stop.set()
try:
    os.set_blocking(1,False)
    initialize_source_mapping()
    if not os.path.isdir(root): raise ValueError('Remote workspace does not exist')
    # No remote write or implicit upload. Verify each reviewed visible file before launching Delve.
    for relative,expected in plan['files'].items():
        if stop.is_set(): raise InterruptedError('Cancelled before Delve startup')
        current=root
        for part in relative.split('/'):
            current=os.path.join(current,part)
            if os.path.islink(current): raise ValueError('Source path contains a symlink: '+relative)
        fd=os.open(current,os.O_RDONLY|os.O_NOFOLLOW)
        with os.fdopen(fd,'rb') as f:
            if not stat.S_ISREG(os.fstat(f.fileno()).st_mode): raise ValueError('Source is not regular: '+relative)
            content=f.read(8*1024*1024+1)
        if len(content)>8*1024*1024 or hashlib.sha256(content).hexdigest()!=expected: raise ValueError('Saved source mismatch: '+relative+'. Synchronize explicitly, then review a new debug session.')
    visited=0
    for directory,dirs,files in os.walk(root,followlinks=False):
        if stop.is_set(): raise InterruptedError('Cancelled before Delve startup')
        visited+=len(dirs)+len(files)
        if visited>10000: raise ValueError('Remote tree exceeds 10000 entries; choose a smaller workspace')
        dirs[:]=[d for d in dirs if not d.startswith('.') and d not in ('build','target','node_modules','__pycache__')]
        for name in files:
            relative=os.path.relpath(os.path.join(directory,name),root)
            if (name.endswith('.go') or name in ('go.mod','go.sum','go.work','go.work.sum')) and relative not in plan['files']:
                raise ValueError('Unmapped remote Go source: '+relative)
    target=os.path.realpath(os.path.join(root,plan['target']))
    if target!=root and not target.startswith(root+'/'): raise ValueError('Package target leaves the remote workspace')
    if plan['mode']=='exec':
        if not os.path.isfile(target) or not os.access(target,os.X_OK): raise ValueError('Exec target must be an executable file within the workspace')
    elif not os.path.isdir(target): raise ValueError('Package target must be a directory')
    dlv=shutil.which(plan['dlv']);go=shutil.which(plan['go'])
    if not dlv or not go: raise ValueError('Remote Go and Delve must already be installed; no tools were installed')
    env=dict(os.environ);env.update(GOWORK='off',GOENV='off',GOFLAGS='-mod=readonly',GOTOOLCHAIN='local',GOPROXY='off',GOSUMDB='off')
    env['PATH']=os.path.dirname(os.path.realpath(go))+os.pathsep+env.get('PATH','')
    private=tempfile.mkdtemp(prefix='goforge-debug-');os.chmod(private,0o700)
    prepare_workspace()
    # Delve applies launch env before invoking Go; SDK discovery must see the same values.
    env.update(plan['arguments'].get('env',{}))
    debug_sdk_initialize()
    debug_module_start()
    threading.Thread(target=debug_sdk_worker,daemon=True).start()
    child=subprocess.Popen([dlv,'dap','--listen=127.0.0.1:0'],cwd=root,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
    ready=queue.Queue();last=[]
    def messages():
        for raw in child.stdout:
            line=raw.decode('utf-8','replace').rstrip();last.append(line)
            if len(last)>8: last.pop(0)
            match=re.search(r'DAP server listening at: 127\\.0\\.0\\.1:(\\d+)',line)
            if match: ready.put(int(match.group(1)))
    threading.Thread(target=messages,daemon=True).start()
    deadline=time.monotonic()+15;port=None
    while not stop.is_set() and time.monotonic()<deadline:
        try: port=ready.get(timeout=.1);break
        except queue.Empty:
            if child.poll() is not None: raise ValueError('Delve exited before listening: '+'; '.join(last))
    if port is None: raise ValueError('Delve did not become ready within 15 seconds')
    conn=socket.create_connection(('127.0.0.1',port),timeout=10);conn.settimeout(None)
    sys.stderr.write('Remote Delve ready: verified source, '+('attach (preserve target)' if attached else plan['mode'])+' via SSH stdio; adapter pid='+str(child.pid)+'\\n');sys.stderr.flush()
    threading.Thread(target=input_loop,daemon=True).start();threading.Thread(target=output_loop,daemon=True).start()
    while not stop.wait(.1): pass
except Exception as e:
    failure=str(e)
finally:
    stop.set()
    if conn:
        try: remote_send({'seq':2000000000,'type':'request','command':'disconnect','arguments':{'terminateDebuggee':not attached}},timeout=.3)
        except OSError:
            try: conn.shutdown(socket.SHUT_RDWR)
            except OSError: pass
    if child:
        try: child.wait(timeout=3)
        except subprocess.TimeoutExpired:
            # Signal Delve itself first: it knows whether to terminate a launch or release an attach.
            child.terminate()
            try: child.wait(timeout=3)
            except subprocess.TimeoutExpired: child.kill();child.wait(timeout=3)
    if conn:
        try: conn.shutdown(socket.SHUT_RDWR)
        except OSError: pass
        conn.close()
    module_cleanup=debug_module_close()
    if private and module_cleanup: shutil.rmtree(private,ignore_errors=True)
    if failure: sys.stderr.write('GoForge remote debug: '+failure+'\\n')
sys.exit(1 if failure else 0)
""";
}
