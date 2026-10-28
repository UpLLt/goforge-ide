package dev.goforge.remote;

/** Original, bounded remote helpers. No installed daemon, password, or remote IDE backend. */
final class GoRemoteScripts {
    private GoRemoteScripts() { }
    static final String FILES = """
import os,sys,json,base64,hashlib,stat
LIMIT=8*1024*1024
root,op,relative=sys.argv[1:4]
root=os.path.realpath(root)
rootfd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
def parts(path):
    result=path.split('/')
    if not path or path.startswith('/') or any(p in ('','.','..') for p in result) or any(ord(c)<32 or ord(c)==127 for c in path):
        raise ValueError('Invalid relative path')
    return result
def directory(path):
    fd=os.dup(rootfd)
    try:
        if path:
            for part in parts(path):
                nxt=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
                os.close(fd);fd=nxt
        return fd
    except:
        os.close(fd);raise
try:
    if op=='check':
        print(json.dumps({'root':root,'python':sys.version.split()[0],'writable':os.access(root,os.W_OK)}))
    elif op=='list':
        fd=directory(relative)
        try:
            entries=[]
            for name in sorted(os.listdir(fd)):
                if len(entries)>=2000: raise ValueError('Directory exceeds 2000 entries; choose a narrower directory')
                info=os.stat(name,dir_fd=fd,follow_symlinks=False)
                kind='directory' if stat.S_ISDIR(info.st_mode) else 'file' if stat.S_ISREG(info.st_mode) else 'link/other'
                entries.append({'name':name,'kind':kind,'bytes':info.st_size})
            print(json.dumps({'entries':entries}))
        finally: os.close(fd)
    elif op=='download':
        components=parts(relative);fd=directory('/'.join(components[:-1]))
        try:
            f=os.open(components[-1],os.O_RDONLY|os.O_NOFOLLOW,dir_fd=fd)
            with os.fdopen(f,'rb') as stream:
                info=os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_size>LIMIT: raise ValueError('Only regular files up to 8 MiB can be downloaded')
                data=stream.read(LIMIT+1)
                if len(data)>LIMIT: raise ValueError('File grew beyond 8 MiB')
            print(json.dumps({'data':base64.b64encode(data).decode(),'sha256':hashlib.sha256(data).hexdigest()}))
        finally: os.close(fd)
    elif op in ('snapshot','analysis'):
        name=parts(relative)
        prefix='goforge-analysis-' if op=='analysis' else 'goforge-upload-'
        if len(name)!=1 or not name[0].startswith(prefix): raise ValueError('Invalid snapshot name')
        packet=sys.stdin.buffer.read(48*1024*1024+1)
        if len(packet)>48*1024*1024: raise ValueError('Snapshot payload too large')
        rows=json.loads(packet)['files']
        if len(rows)>2000: raise ValueError('Too many files')
        decoded=[];seen=set();total=0
        for row in rows:
            components=parts(row['path']);data=base64.b64decode(row['data'],validate=True);total+=len(data)
            if row['path'] in seen or len(data)>LIMIT or total>32*1024*1024: raise ValueError('Snapshot bounds exceeded')
            if hashlib.sha256(data).hexdigest()!=row['sha256']: raise ValueError('Snapshot content hash mismatch')
            seen.add(row['path']);decoded.append((components,data))
        os.mkdir(name[0],0o700,dir_fd=rootfd)
        target=os.open(name[0],os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=rootfd)
        try:
            for components,data in decoded:
                parent=os.dup(target)
                try:
                    for part in components[:-1]:
                        try: os.mkdir(part,0o700,dir_fd=parent)
                        except FileExistsError: pass
                        nxt=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
                        os.close(parent);parent=nxt
                    fd=os.open(components[-1],os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=parent)
                    with os.fdopen(fd,'wb') as stream: stream.write(data)
                finally: os.close(parent)
            if op=='analysis':
                marker={'name':name[0],'files':{row['path']:row['sha256'] for row in rows}}
                fd=os.open('.goforge-analysis.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=target)
                with os.fdopen(fd,'w') as stream: json.dump(marker,stream)
        finally: os.close(target)
        print(json.dumps({'directory':root+'/'+name[0],'files':len(decoded),'bytes':total}))
    else: raise ValueError('Unknown operation')
finally: os.close(rootfd)
""";

    static final String GO = """
import os,sys,subprocess,threading,signal,time
root,go,action=sys.argv[1:4]
commands={'env':['env','-json'],'build':['build','./...'],'test':['test','-json','./...'],'run':['run','.']}
if action not in commands: raise ValueError('Unknown Go action')
stop=threading.Event()
def lost_connection():
    try:
        while os.read(sys.stdin.fileno(),1024): pass
    finally: stop.set()
threading.Thread(target=lost_connection,daemon=True).start()
for sig in (signal.SIGTERM,signal.SIGHUP,signal.SIGINT): signal.signal(sig,lambda *_:stop.set())
child=subprocess.Popen([go]+commands[action],cwd=os.path.realpath(root),start_new_session=True,stdin=subprocess.DEVNULL)
print('GoForge remote process: '+str(child.pid),file=sys.stderr,flush=True)
cancelled=False
try:
    while child.poll() is None:
        if stop.wait(0.1): cancelled=True;break
finally:
    try: os.killpg(child.pid,signal.SIGTERM)
    except ProcessLookupError: pass
    if child.poll() is None:
        try: child.wait(timeout=2)
        except subprocess.TimeoutExpired:
            pass
    # A launcher can exit before its children, including a child that ignores TERM.
    try: os.killpg(child.pid,signal.SIGKILL)
    except ProcessLookupError: pass
    child.wait()
sys.exit(130 if cancelled else child.returncode)
""";
    static String python(String script, String... arguments) {
        // The source is trusted but contains line breaks; quote it without the path control-character restriction.
        var command = new StringBuilder("python3 -c '").append(script.replace("'", "'\"'\"'")).append("'");
        for (String argument : arguments) command.append(' ').append(GoRemoteProfile.quote(argument));
        return command.toString();
    }
}
