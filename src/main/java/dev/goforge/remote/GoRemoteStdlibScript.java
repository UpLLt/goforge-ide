package dev.goforge.remote;

/** Bounded read-only SDK helpers injected into the owned SSH supervisor before gopls starts. */
final class GoRemoteStdlibScript {
    private GoRemoteStdlibScript(){}
    static final String SOURCE="""
SDK_FILE_LIMIT=2*1024*1024
sdkfd=None;sdkinfo=None;sdkstate=None
sdkqueue=queue.Queue(maxsize=16)
def sdk_identity(info):return (info.st_dev,info.st_ino,info.st_size,info.st_mtime_ns,info.st_ctime_ns)
def sdk_read_fd(fd,limit):
    before=os.fstat(fd)
    if not stat.S_ISREG(before.st_mode) or before.st_size>limit:raise ValueError('SDK source is not a bounded regular file')
    chunks=[];count=0
    while count<=limit:
        chunk=os.read(fd,min(65536,limit+1-count))
        if not chunk:break
        chunks.append(chunk);count+=len(chunk)
    after=os.fstat(fd)
    if count>limit or count!=before.st_size or sdk_identity(before)!=sdk_identity(after):raise ValueError('SDK source changed during read')
    return b''.join(chunks),sdk_identity(after)
def sdk_read_file(path,limit):
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    try:return sdk_read_fd(fd,limit)
    finally:os.close(fd)
def sdk_env(executable):
    process=subprocess.Popen([executable,'env','-json','GOROOT','GOVERSION','GOTOOLDIR','GOOS','GOARCH'],cwd=root,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
    values={};errors=[];overflow=threading.Event()
    def collect(key,stream,limit):
        try:
            data=stream.read(limit+1)
            if len(data)>limit:overflow.set()
            values[key]=data
        except Exception as error:errors.append(error)
    threads=[threading.Thread(target=collect,args=('out',process.stdout,65536),daemon=True),threading.Thread(target=collect,args=('err',process.stderr,8192),daemon=True)]
    for thread in threads:thread.start()
    deadline=time.monotonic()+8
    try:
        while process.poll() is None:
            if overflow.is_set() or time.monotonic()>deadline:raise ValueError('Remote SDK query exceeded its bound')
            time.sleep(.02)
        for thread in threads:thread.join(timeout=1)
        if any(thread.is_alive() for thread in threads) or errors or overflow.is_set() or process.returncode!=0:raise ValueError('Remote SDK query failed')
        return json.loads(values['out'].decode('utf-8','strict'))
    finally:
        try:os.killpg(process.pid,signal.SIGKILL)
        except ProcessLookupError:pass
        process.wait(timeout=2);process.stdout.close();process.stderr.close()
def sdk_initialize():
    global sdkfd,sdkinfo,sdkstate
    resolved=shutil.which('go',path=env.get('PATH',''))
    if not resolved:raise ValueError('Remote Go executable is unavailable')
    executable=os.path.realpath(resolved)
    _,goidentity=sdk_read_file(executable,64*1024*1024)
    settings=sdk_env(executable)
    goroot=os.path.realpath(settings['GOROOT']);source=os.path.join(goroot,'src')
    if not os.path.isabs(goroot) or any(ord(c)<32 for c in source):raise ValueError('Invalid remote SDK root')
    rootinfo=os.stat(goroot,follow_symlinks=False)
    sourcefd=os.open(source,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        versiondata,versionidentity=sdk_read_file(os.path.join(goroot,'VERSION'),8192)
        if sdk_identity(os.stat(executable,follow_symlinks=False))!=goidentity:raise ValueError('Remote Go executable changed during SDK discovery')
        sourceinfo=os.fstat(sourcefd)
        identity={'executable':executable,'goIdentity':goidentity,'root':goroot,'rootIdentity':(rootinfo.st_dev,rootinfo.st_ino),'sourceIdentity':(sourceinfo.st_dev,sourceinfo.st_ino),'versionIdentity':versionidentity,'versionSha256':hashlib.sha256(versiondata).hexdigest(),'settings':settings}
        token=hashlib.sha256(json.dumps(identity,sort_keys=True).encode('utf-8')).hexdigest()
        sdkinfo={'root':source,'token':token,'version':settings['GOVERSION']};sdkstate=identity;sdkfd=sourcefd
    except:
        os.close(sourcefd);raise
    # Use the discovered SDK for this gopls process, including its own go invocations.
    env['GOROOT']=goroot
    return sdkinfo
def sdk_source(relative,token):
    if sdkfd is None or token!=sdkinfo['token']:raise ValueError('Remote SDK identity is unavailable or changed')
    items=relative.split('/')
    if not relative.endswith('.go') or len(relative)>4096 or len(items)>64 or any(p in ('','.','..') or chr(92) in p or any(ord(c)<32 or ord(c)==127 for c in p) for p in items):raise ValueError('Invalid SDK source path')
    current=os.stat(sdkstate['root'],follow_symlinks=False);source=os.stat(sdkinfo['root'],follow_symlinks=False)
    if (current.st_dev,current.st_ino)!=sdkstate['rootIdentity'] or (source.st_dev,source.st_ino)!=sdkstate['sourceIdentity'] or not stat.S_ISDIR(source.st_mode):raise ValueError('Remote SDK root changed; restart analysis')
    if sdk_identity(os.stat(sdkstate['executable'],follow_symlinks=False))!=sdkstate['goIdentity']:raise ValueError('Remote Go executable changed; restart analysis')
    versiondata,versionidentity=sdk_read_file(os.path.join(sdkstate['root'],'VERSION'),8192)
    if versionidentity!=sdkstate['versionIdentity'] or hashlib.sha256(versiondata).hexdigest()!=sdkstate['versionSha256']:raise ValueError('Remote SDK version changed; restart analysis')
    directory=os.dup(sdkfd)
    try:
        for item in items[:-1]:
            nxt=os.open(item,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=directory);os.close(directory);directory=nxt
        fd=os.open(items[-1],os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=directory)
        try:data,fileidentity=sdk_read_fd(fd,SDK_FILE_LIMIT)
        finally:os.close(fd)
        text=data.decode('utf-8','strict')
        if chr(0) in text:raise ValueError('Remote Go source contains NUL')
        # Re-walk from the pinned root after reading: renamed/replaced intermediate
        # directories must not make old opened handles look like the current URI.
        verification=os.dup(sdkfd)
        try:
            for item in items[:-1]:
                nxt=os.open(item,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=verification);os.close(verification);verification=nxt
            verifyfd=os.open(items[-1],os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=verification)
            try:
                if sdk_identity(os.fstat(verifyfd))!=fileidentity:raise ValueError('Remote SDK source path changed during read')
            finally:os.close(verifyfd)
        finally:os.close(verification)
        rootnow=os.stat(sdkstate['root'],follow_symlinks=False);sourcenow=os.stat(sdkinfo['root'],follow_symlinks=False)
        if (rootnow.st_dev,rootnow.st_ino)!=sdkstate['rootIdentity'] or (sourcenow.st_dev,sourcenow.st_ino)!=sdkstate['sourceIdentity'] or not stat.S_ISDIR(sourcenow.st_mode):raise ValueError('Remote SDK root changed during read')
        if sdk_identity(os.stat(sdkstate['executable'],follow_symlinks=False))!=sdkstate['goIdentity'] or sdk_identity(os.stat(os.path.join(sdkstate['root'],'VERSION'),follow_symlinks=False))!=sdkstate['versionIdentity']:raise ValueError('Remote SDK identity changed during read')
        return {'path':relative,'token':token,'sha256':hashlib.sha256(data).hexdigest(),'data':base64.b64encode(data).decode('ascii')}
    finally:os.close(directory)
def sdk_worker():
    while not stopping.is_set():
        try:request=sdkqueue.get(timeout=.1)
        except queue.Empty:continue
        try:response=sdk_source(request['path'],request['token'])
        except Exception as error:response={'error':'Remote standard-library source could not be read safely: '+str(error)}
        response['id']=request['id']
        if not stopping.is_set():send({'jsonrpc':'2.0','method':'goforge/stdlibSource','params':response})
""";
}
