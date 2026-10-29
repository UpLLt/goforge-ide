package dev.goforge.remote;

/** Optional, session-owned selected-cache source capability for paused remote debug frames. */
final class GoRemoteDebugModuleScript {
    private GoRemoteDebugModuleScript() {}
    static final String SOURCE="debug_module_reader=base64.b64decode('"+
            java.util.Base64.getEncoder().encodeToString(GoRemoteModuleScript.SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"').decode('utf-8')\n"+"""
# The shared reader runs in a private namespace rooted in this session's own snapshot.
debug_module_context=None;debug_module_thread=None;debug_module_status='unavailable';debug_module_started=False
debug_module_access=threading.Lock();debug_module_catalog=None
class DebugModulePauseExpired(ValueError):pass

def debug_module_original(context):
    current=os.stat(root,follow_symlinks=False)
    if not stat.S_ISDIR(current.st_mode) or not os.path.samestat(os.fstat(context['originalfd']),current):
        raise ValueError('Reviewed debug project root changed')
    if os.path.lexists(os.path.join(root,'vendor')):raise ValueError('Vendor sources are unavailable')

def debug_module_metadata(context,name):
    # These are the immutable launch-review hashes, never a newly discovered baseline.
    if name not in ('go.mod','go.sum') or name not in plan['files']:raise ValueError('Unreviewed dependency metadata')
    debug_module_original(context)
    fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=context['originalfd'])
    try:data,identity=sdk_read_fd(fd,2*1024*1024)
    finally:os.close(fd)
    if sdk_identity(os.stat(name,dir_fd=context['originalfd'],follow_symlinks=False))!=identity:raise ValueError('Dependency metadata path changed')
    if hashlib.sha256(data).hexdigest()!=plan['files'][name]:raise ValueError('Dependency metadata changed since debug review')
    debug_module_original(context)
    return data,identity

def debug_module_initialize():
    global debug_module_catalog,debug_module_status
    context=debug_module_context
    try:
        namespace=context['namespace']
        catalogue=namespace['module_initialize']()
        for name in ('go.mod','go.sum'):debug_module_metadata(context,name)
        with debug_module_access:
            if stop.is_set():return
            debug_module_catalog=catalogue;debug_module_status='ready'
        sys.stderr.write('Remote selected dependency sources ready: '+str(len(catalogue['modules']))+' cached selected modules.\\n');sys.stderr.flush()
    except Exception as error:
        if stop.is_set():debug_module_status='stopped';return
        debug_module_status='unavailable'
        sys.stderr.write('Selected dependency source navigation is unavailable: '+str(error)[:400]+'. Project and SDK debugging remain available.\\n');sys.stderr.flush()

def debug_module_start():
    global debug_module_context,debug_module_thread,debug_module_status,debug_module_started
    if debug_module_started:return
    debug_module_started=True
    if plan['mode'] not in ('debug','test') or not sdkstate:return
    if (plan.get('workspace',{}).get('workFile') or 'go.work' in plan['files']
            or 'go.mod' not in plan['files'] or 'go.sum' not in plan['files']
            or sum(path=='go.mod' or path.endswith('/go.mod') for path in plan['files'])!=1):
        sys.stderr.write('Selected dependency source navigation requires one reviewed root module and go.sum; project and SDK debugging remain available.\\n');sys.stderr.flush();return
    context={'namespace':None,'originalfd':None,'rootfd':None}
    debug_module_context=context
    try:
        context['originalfd']=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        debug_module_original(context)
        metadata={name:debug_module_metadata(context,name)[0] for name in ('go.mod','go.sum')}
        directory=os.path.join(private,'dependency-source-snapshot');os.mkdir(directory,0o700)
        context['rootfd']=os.open(directory,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        for name,data in metadata.items():
            fd=os.open(name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=context['rootfd'])
            with os.fdopen(fd,'wb') as stream:stream.write(data)
        namespace={name:globals()[name] for name in ('os','sys','json','hashlib','stat','subprocess','threading','queue','re','time','signal','base64','sdk_identity','sdk_read_fd','SDK_FILE_LIMIT')}
        namespace.update(uuid=__import__('uuid'),root=directory,rootfd=context['rootfd'],expected=dict(plan['files']),
            metadata_hashes={name:plan['files'][name] for name in ('go.mod','go.sum')},workfile=None,
            env=dict(env),sdkstate=sdkstate,stopping=stop,
            read_data=lambda name:debug_module_metadata(context,name),
            read_owned=lambda name:(hashlib.sha256(debug_module_metadata(context,name)[0]).hexdigest(),None))
        exec(debug_module_reader,namespace)
        # A read also observes its pause and shared frame-batch deadline; discovery only observes Stop.
        check=namespace['module_check'];local=threading.local();context['reading']=local
        def checked(deadline):
            epoch=getattr(local,'epoch',None)
            if epoch is not None:
                if time.monotonic()>min(deadline,getattr(local,'deadline',deadline)):raise DebugModulePauseExpired('Dependency source batch expired')
                with debug_sdk_lock:
                    if not debug_sdk_paused or epoch!=debug_sdk_epoch:raise DebugModulePauseExpired('Dependency source pause expired')
            check(deadline)
        namespace['module_check']=checked;context['namespace']=namespace
        debug_module_status='loading'
        debug_module_thread=threading.Thread(target=debug_module_initialize,daemon=True);debug_module_thread.start()
    except Exception as error:
        if stop.is_set():debug_module_status='stopped';return
        debug_module_status='unavailable'
        sys.stderr.write('Selected dependency source navigation is unavailable: '+str(error)[:400]+'. Project and SDK debugging remain available.\\n');sys.stderr.flush()

def debug_module_match(path):
    # No guessed path/token while the optional catalogue is pending or unavailable.
    if not isinstance(path,str) or debug_module_catalog is None:return None
    for record in debug_module_catalog['modules']:
        if path.startswith(record['root']+'/'):
            return record,path[len(record['root'])+1:]
    return None

def debug_module_read(record,relative,epoch,deadline):
    if stop.is_set() or time.monotonic()>=deadline:raise ValueError('Dependency source expired')
    if not debug_module_access.acquire(timeout=max(0,min(1,deadline-time.monotonic()))):raise ValueError('Dependency source reader is busy')
    try:
        if stop.is_set() or debug_module_status!='ready':raise ValueError('Dependency source catalogue unavailable')
        context=debug_module_context;local=context['reading'];local.epoch=epoch;local.deadline=deadline
        namespace=context['namespace'];capability=namespace['module_records'].get(record['token'])
        previously_revoked=capability is None or capability['revoked']
        try:return namespace['module_source'](relative,record['token'])
        except DebugModulePauseExpired:
            # The shared reader revokes on every exception. A pause/batch cancellation does
            # not establish changed source; retry still revalidates the full proof next pause.
            if not previously_revoked and not stop.is_set():capability['revoked']=False
            raise
        except Exception as error:
            # ZIP CRC/decompression failures are optional-source failures, never DAP transport failures.
            raise ValueError('Selected module source could not be verified') from error
        finally:
            local.epoch=None;local.deadline=float('inf')
    finally:debug_module_access.release()

def debug_module_unavailable(source,path):
    if not isinstance(path,str) or not sdkstate:return
    cache=os.path.realpath(sdkstate['settings']['GOMODCACHE'])
    if path.startswith(cache+'/'):
        reason='not selected, changed, or unsupported' if debug_module_status=='ready' else debug_module_status
        source.clear();source.update(name='Selected dependency source unavailable ('+reason+')',sourceReference=0,presentationHint='deemphasize')

def debug_module_close():
    global debug_module_catalog,debug_module_status
    debug_module_catalog=None;debug_module_status='stopped'
    if debug_module_thread:debug_module_thread.join(timeout=3)
    if debug_module_thread and debug_module_thread.is_alive():
        sys.stderr.write('Dependency catalogue worker did not stop; owned debug snapshot retained.\\n');return False
    if not debug_module_access.acquire(timeout=3):
        sys.stderr.write('Dependency source reader did not stop; owned debug snapshot retained.\\n');return False
    try:
        context=debug_module_context
        if context:
            namespace=context.get('namespace')
            failed=False
            for container,key in ((namespace,'modulefd'),(context,'rootfd'),(context,'originalfd')):
                if container is None:continue
                descriptor=container.get(key);container[key]=None
                if descriptor is not None:
                    try:os.close(descriptor)
                    except OSError as error:
                        failed=True;sys.stderr.write('Dependency source descriptor close failed ('+key+'); owned debug snapshot retained: '+str(error)[:200]+'\\n')
            if failed:return False
        return True
    finally:debug_module_access.release()
""";
}
