package dev.goforge.remote;

/** Transient Python stdio supervisor; executed through SSH, never installed on the remote host. */
final class GoRemoteAnalysisScript {
    private GoRemoteAnalysisScript(){}
    static final String SOURCE="""
import os,sys,json,base64,hashlib,stat,subprocess,threading,signal,time,uuid,re,queue,shutil
root,name,gopls,go,workfile,runtimework=sys.argv[1:7]
LIMIT=16*1024*1024
stopping=threading.Event();write_lock=threading.Lock()
def send(packet):
    body=json.dumps(packet,separators=(',',':'),ensure_ascii=False).encode('utf-8')
    with write_lock:
        sys.stdout.buffer.write(('Content-Length: %d\\r\\n\\r\\n'%len(body)).encode('ascii')+body);sys.stdout.buffer.flush()
def frame(stream):
    length=None;total=0
    while True:
        line=stream.readline(8193);total+=len(line)
        if not line:
            if total==0:return None
            raise ValueError('Incomplete protocol header')
        if total>8192:raise ValueError('Protocol header limit')
        if line in (b'\\r\\n',b'\\n'):break
        key,sep,value=line.partition(b':')
        if not sep:raise ValueError('Invalid protocol header')
        if key.lower()==b'content-length':
            if length is not None:raise ValueError('Duplicate length')
            length=int(value.strip())
    if length is None or length<0 or length>LIMIT:raise ValueError('Protocol size limit')
    body=stream.read(length)
    if len(body)!=length:raise ValueError('Incomplete protocol body')
    return json.loads(body)
def parts(path):
    items=path.split('/')
    if not path or path.startswith('/') or any(p in ('','.','..') or p.startswith('.') for p in items):raise ValueError('Invalid snapshot path')
    return items
if os.path.basename(root)!=name or not name.startswith('goforge-analysis-'):raise ValueError('Invalid snapshot identity')
rootfd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
fd=os.open('.goforge-analysis.json',os.O_RDONLY|os.O_NOFOLLOW,dir_fd=rootfd)
with os.fdopen(fd) as stream:marker=json.load(stream)
if marker['name']!=name:raise ValueError('Snapshot identity mismatch')
expected=marker['files']
if workfile not in ('','go.work') or bool(workfile)!=bool(runtimework):raise ValueError('Invalid reviewed workspace binding')
if runtimework and not re.fullmatch(r'[.]goforge-runtime-[a-f0-9-]{36}[.]work',runtimework):raise ValueError('Invalid private workspace identity')
if workfile and workfile not in expected:raise ValueError('Workspace file was not reviewed')
if not workfile and 'go.work' in expected:raise ValueError('Snapshot go.work must be reviewed before analysis')
def metadata(path):return path.split('/')[-1] in ('go.mod','go.sum','go.work','go.work.sum')
metadata_hashes={path:digest for path,digest in expected.items() if metadata(path)}
def parent(path):
    items=parts(path);fd=os.dup(rootfd)
    try:
        for item in items[:-1]:
            nxt=os.open(item,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd);os.close(fd);fd=nxt
        return fd,items[-1]
    except:
        os.close(fd);raise
def read_data(path):
    directory,leaf=parent(path)
    try:
        fd=os.open(leaf,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=directory)
        with os.fdopen(fd,'rb') as stream:
            info=os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size>8*1024*1024:raise ValueError('Invalid snapshot file')
            data=stream.read(8*1024*1024+1)
        return data,(info.st_dev,info.st_ino,info.st_mtime_ns,info.st_size)
    finally:os.close(directory)
def read_owned(path):
    data,identity=read_data(path)
    return hashlib.sha256(data).hexdigest(),identity
def sync(rows):
    prepared=[];seen=set();total=0
    for row in rows:
        path=row['path']
        if path not in expected or path in seen:raise ValueError('Snapshot file set changed; rebuild the analysis snapshot')
        seen.add(path);data=base64.b64decode(row['data'],validate=True);total+=len(data)
        if len(data)>8*1024*1024 or total>32*1024*1024:raise ValueError('Save size limit')
        if metadata(path) and hashlib.sha256(data).hexdigest()!=metadata_hashes[path]:raise ValueError('Workspace/module metadata changed; create a fresh reviewed analysis snapshot')
        digest,identity=read_owned(path)
        if digest!=expected[path]:raise ValueError('Remote snapshot conflict: a file was changed outside this analysis session. Local files were preserved; restart analysis')
        prepared.append((path,data,identity))
    for path,data,identity in prepared:
        directory,leaf=parent(path);temporary='.goforge-save-'+uuid.uuid4().hex
        try:
            current=os.stat(leaf,dir_fd=directory,follow_symlinks=False)
            if (current.st_dev,current.st_ino,current.st_mtime_ns,current.st_size)!=identity:raise ValueError('Remote snapshot changed during save')
            fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=directory)
            with os.fdopen(fd,'wb') as stream:stream.write(data)
            os.replace(temporary,leaf,src_dir_fd=directory,dst_dir_fd=directory)
            expected[path]=hashlib.sha256(data).hexdigest()
        finally:
            try:os.unlink(temporary,dir_fd=directory)
            except FileNotFoundError:pass
            os.close(directory)
    # Persist the accepted hashes so a normal LSP wrapper restart cannot relabel a conflict.
    temp='.goforge-manifest-'+uuid.uuid4().hex
    try:
        fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=rootfd)
        with os.fdopen(fd,'w') as stream:json.dump({'name':name,'files':expected},stream)
        os.replace(temp,'.goforge-analysis.json',src_dir_fd=rootfd,dst_dir_fd=rootfd)
    finally:
        try:os.unlink(temp,dir_fd=rootfd)
        except FileNotFoundError:pass
for path,digest in expected.items():
    if read_owned(path)[0]!=digest:raise ValueError('Remote snapshot changed before analysis started')
env=dict(os.environ);env.update({'GOWORK':'off','GOENV':'off','GOFLAGS':'-mod=readonly','GOPACKAGESDRIVER':'off','GOTOOLCHAIN':'local','GOPROXY':'off','GOSUMDB':'off'})
if go.startswith('/'):env['PATH']=os.path.dirname(go)+os.pathsep+env.get('PATH','')
"""+GoRemoteStdlibScript.SOURCE+"""
private_files=[]
def cleanup_private():
    for leaf in private_files:
        try:os.unlink(leaf,dir_fd=rootfd)
        except FileNotFoundError:pass
def private_copy(source,leaf):
    data,_=read_data(source)
    fd=os.open(leaf,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=rootfd)
    private_files.append(leaf)
    with os.fdopen(fd,'wb') as stream:stream.write(data)
try:
    if workfile:
        # Same-directory copies preserve relative use/replace paths. Go may write a work sum,
        # so only the supervisor's private copy is selected as GOWORK, never reviewed metadata.
        private_copy(workfile,runtimework)
        if 'go.work.sum' in expected:private_copy('go.work.sum',runtimework+'.sum')
        else:
            fd=os.open(runtimework+'.sum',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=rootfd);os.close(fd);private_files.append(runtimework+'.sum')
        env['GOWORK']=os.path.join(root,runtimework)
    if not os.path.samestat(os.fstat(rootfd),os.stat(root,follow_symlinks=False)):raise ValueError('Snapshot root was replaced before startup')
    try:sdk_initialize()
    except Exception:sdkinfo={'error':'Remote SDK source identity is unavailable; project analysis remains active'}
    child=subprocess.Popen([gopls,'serve'],cwd=root,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
except:
    cleanup_private();os.close(rootfd);raise
for sig in (signal.SIGTERM,signal.SIGHUP,signal.SIGINT):signal.signal(sig,lambda *_:stopping.set())
def input_loop():
    try:
        while not stopping.is_set():
            packet=frame(sys.stdin.buffer)
            if packet is None:break
            for path,digest in metadata_hashes.items():
                if read_owned(path)[0]!=digest:raise ValueError('Remote workspace/module metadata changed outside this session; create a fresh analysis snapshot')
            if packet.get('method')=='goforge/readStdlib':
                request=packet['params']
                if set(request)!=set(('id','token','path')) or not all(isinstance(request[k],str) for k in request):raise ValueError('Invalid SDK read request')
                try:sdkqueue.put_nowait(request)
                except queue.Full:send({'jsonrpc':'2.0','method':'goforge/stdlibSource','params':{'id':request['id'],'error':'Remote source read queue is full'}})
                continue
            if packet.get('method')=='goforge/snapshotSync':
                sync(packet['params']['files']);packet=packet['params']['message']
            if str(packet.get('method','')).startswith('goforge/'):raise ValueError('Reserved bridge method')
            body=json.dumps(packet,separators=(',',':'),ensure_ascii=False).encode('utf-8')
            child.stdin.write(('Content-Length: %d\\r\\n\\r\\n'%len(body)).encode('ascii')+body);child.stdin.flush()
    except Exception as error:
        send({'jsonrpc':'2.0','method':'goforge/bridgeFailure','params':{'message':str(error)}})
    finally:stopping.set()
def output_loop():
    try:
        while not stopping.is_set():
            packet=frame(child.stdout)
            if packet is None:break
            if str(packet.get('method','')).startswith('goforge/'):continue
            send(packet)
    finally:stopping.set()
def drain_errors():
    # Never forward arbitrary server stderr (which can contain source text or environment values).
    while child.stderr.read(4096):pass
threading.Thread(target=input_loop,daemon=True).start();threading.Thread(target=output_loop,daemon=True).start();threading.Thread(target=drain_errors,daemon=True).start()
threading.Thread(target=sdk_worker,daemon=True).start()
send({'jsonrpc':'2.0','method':'goforge/bridgeReady','params':{'pid':child.pid,'stdlib':sdkinfo}})
try:
    while child.poll() is None and not stopping.wait(.05):pass
finally:
    try:os.killpg(child.pid,signal.SIGTERM)
    except ProcessLookupError:pass
    try:child.wait(timeout=2)
    except subprocess.TimeoutExpired:pass
    try:os.killpg(child.pid,signal.SIGKILL)
    except ProcessLookupError:pass
    child.wait();cleanup_private();os.close(rootfd)
    if sdkfd is not None:os.close(sdkfd)
""";
}
