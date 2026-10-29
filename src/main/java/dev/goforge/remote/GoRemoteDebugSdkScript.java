package dev.goforge.remote;

/** Selected-SDK snapshots for observed paused frames; never a general remote file reader. */
final class GoRemoteDebugSdkScript {
    private GoRemoteDebugSdkScript() {}
    static final String SOURCE=GoRemoteStdlibScript.SOURCE+"""
debug_sdk_lock=threading.RLock()
debug_sdk_epoch=0;debug_sdk_paused=False;debug_sdk_pending={}
debug_sdk_refs={};debug_sdk_paths={};debug_sdk_bytes=0;debug_sdk_enabled=False
debug_sdk_queue=queue.Queue(maxsize=16)
def debug_sdk_initialize():
    global debug_sdk_enabled
    if plan['mode'] not in ('debug','test'):return
    try:
        sdk_initialize();debug_sdk_enabled=True
    except Exception:
        sys.stderr.write('Selected SDK source navigation is unavailable for this debug session. Workspace debugging remains available.\\n');sys.stderr.flush()
def debug_sdk_request(message):
    global debug_sdk_paused,debug_sdk_epoch
    command=message.get('command')
    with debug_sdk_lock:
        if command in ('continue','next','stepIn','stepOut','restart','disconnect','terminate'):
            debug_sdk_paused=False;debug_sdk_epoch+=1;debug_sdk_pending.clear()
        if command=='stackTrace' and debug_sdk_enabled and debug_sdk_paused:
            ident=message.get('seq')
            if type(ident) is int and ident>0 and len(debug_sdk_pending)<64:
                debug_sdk_pending[ident]=debug_sdk_epoch
def debug_sdk_output(message):
    global debug_sdk_paused,debug_sdk_epoch
    allowed=False
    with debug_sdk_lock:
        if message.get('type')=='event':
            if message.get('event')=='stopped':
                debug_sdk_epoch+=1;debug_sdk_paused=True;debug_sdk_pending.clear()
            elif message.get('event') in ('continued','terminated','exited'):
                debug_sdk_epoch+=1;debug_sdk_paused=False;debug_sdk_pending.clear()
        if message.get('type')=='response' and message.get('command')=='stackTrace':
            epoch=debug_sdk_pending.pop(message.get('request_seq'),None)
            allowed=message.get('success') is True and debug_sdk_paused and epoch==debug_sdk_epoch
    if not allowed:return
    frames=message.get('body',{}).get('stackFrames',[])
    if not isinstance(frames,list) or len(frames)>1000:return
    module_budget=[8,8*1024*1024];module_deadline=time.monotonic()+15
    for frame in frames:
        if not isinstance(frame,dict):continue
        source=frame.get('source')
        if not isinstance(source,dict):continue
        path=source.get('path')
        if not isinstance(path,str):continue
        if sdkinfo and path.startswith(sdkinfo['root']+'/'):
            relative=path[len(sdkinfo['root'])+1:]
            try:
                reference,digest=debug_sdk_capture(relative,epoch,frame.get('line'))
                source.clear();source.update(name=relative,sourceReference=reference,
                    origin='Selected remote SDK '+sdkinfo['version'],
                    adapterData={'goforgeSdkSource':1,'version':sdkinfo['version'],'relative':relative,'sha256':digest})
            except (OSError,ValueError,InterruptedError):pass
        else:
            match=debug_module_match(path)
            try:
                if match is None:raise ValueError('Unselected dependency path')
                record,relative=match
                if module_budget[0]<=0 or module_budget[1]<=0:raise ValueError('Dependency frame batch limit')
                module_budget[0]-=1
                reference,digest,size=debug_module_capture(record,relative,epoch,frame.get('line'),module_deadline,module_budget[1])
                module_budget[1]-=size
                source.clear();source.update(name=relative,sourceReference=reference,
                    origin='Selected remote module '+record['path']+'@'+record['version'],
                    adapterData={'goforgeModuleSource':1,'module':record['path'],'version':record['version'],'relative':relative,'sha256':digest})
            except (OSError,ValueError,InterruptedError):debug_module_unavailable(source,path)
def debug_module_capture(module,relative,epoch,line,deadline,remaining):
    if type(line) is not int or line<1:raise ValueError('Dependency frame line is invalid')
    key=('module',module['path'],module['version'],relative)
    with debug_sdk_lock:
        if stop.is_set() or not debug_sdk_paused or epoch!=debug_sdk_epoch:raise InterruptedError('Pause expired')
        if key not in debug_sdk_paths and len(debug_sdk_paths)>=128:raise ValueError('Source file limit')
    result=debug_module_read(module,relative,epoch,deadline)
    data=base64.b64decode(result['data'],validate=True);text=data.decode('utf-8','strict')
    if len(data)>remaining or line>len(text.splitlines()):raise ValueError('Dependency frame or batch limit')
    reference,digest=debug_source_store(key,relative,epoch,result,data,text,module)
    return reference,digest,len(data)
def debug_sdk_capture(relative,epoch,line):
    global debug_sdk_bytes
    if type(line) is not int or line<1:raise ValueError('SDK frame line is invalid')
    with debug_sdk_lock:
        if stop.is_set() or not debug_sdk_paused or epoch!=debug_sdk_epoch:raise InterruptedError('Pause expired')
        if ('sdk',relative) not in debug_sdk_paths and len(debug_sdk_paths)>=128:raise ValueError('SDK source file limit')
    result=sdk_source(relative,sdkinfo['token'])
    data=base64.b64decode(result['data'],validate=True);text=data.decode('utf-8','strict')
    if line>len(text.splitlines()):raise ValueError('SDK frame line is invalid')
    return debug_source_store(('sdk',relative),relative,epoch,result,data,text,None)
def debug_source_store(key,relative,epoch,result,data,text,module):
    global debug_sdk_bytes
    with debug_sdk_lock:
        if stop.is_set() or not debug_sdk_paused or epoch!=debug_sdk_epoch:raise InterruptedError('Pause expired')
        previous=debug_sdk_paths.get(key)
        if previous is not None:
            record=debug_sdk_refs[previous]
            if result['sha256']!=record['sha256']:raise ValueError('Selected source changed')
            record['epoch']=epoch;return previous,record['sha256']
        if len(debug_sdk_paths)>=128 or debug_sdk_bytes+len(data)>32*1024*1024:raise ValueError('Source cache limit')
        reference=1000000+len(debug_sdk_refs)
        debug_sdk_refs[reference]={'relative':relative,'sha256':result['sha256'],'text':text,'epoch':epoch,'module':module}
        debug_sdk_paths[key]=reference;debug_sdk_bytes+=len(data)
        return reference,result['sha256']
def debug_sdk_source(request):
    try:debug_sdk_queue.put_nowait(request)
    except queue.Full:response(request,'SDK source requests exceeded the session limit')
def debug_sdk_worker():
    while not stop.is_set():
        try:request=debug_sdk_queue.get(timeout=.1)
        except queue.Empty:continue
        try:debug_sdk_read(request)
        except (BrokenPipeError,OSError):stop.set()
def debug_sdk_read(request):
    args=request.get('arguments',{})
    if not isinstance(args,dict):response(request,'SDK source arguments are invalid');return
    reference=args.get('sourceReference')
    with debug_sdk_lock:
        record=debug_sdk_refs.get(reference) if type(reference) is int else None
        if (stop.is_set() or not debug_sdk_enabled or not debug_sdk_paused or record is None
                or record['epoch']!=debug_sdk_epoch or set(args)!={'sourceReference'}):
            response(request,'Source is not an observed selected frame in the current pause');return
        epoch=debug_sdk_epoch;relative=record['relative'];digest=record['sha256']
    try:
        # Re-read through the pinned no-follow SDK reader; changed source/SDK is not silently reused.
        current=(debug_module_read(record['module'],relative,epoch,time.monotonic()+15) if record.get('module')
                 else sdk_source(relative,sdkinfo['token']))
        if current['sha256']!=digest:raise ValueError('Selected SDK source changed')
        with debug_sdk_lock:
            if stop.is_set() or not debug_sdk_paused or debug_sdk_epoch!=epoch:raise InterruptedError('Pause expired')
            send({'seq':0,'type':'response','request_seq':request.get('seq',0),'command':'source','success':True,
                  'body':{'content':record['text'],'mimeType':'text/plain'}})
    except (OSError,ValueError,InterruptedError):
        if not stop.is_set():response(request,'Selected source changed, expired, or could not be read safely')
""";
}
