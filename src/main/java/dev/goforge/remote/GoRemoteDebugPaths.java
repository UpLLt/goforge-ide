package dev.goforge.remote;

/** The bridge's exact Python path mapper, separately executable for cross-OS protocol regression. */
public final class GoRemoteDebugPaths {
    private GoRemoteDebugPaths() {}
    public static final String SOURCE="""
import ntpath
# The SSH host is POSIX; the IDE source root may use Windows drive or UNC syntax.
def initialize_source_mapping():
    global local_windows,local_display_root,local_key_root,local_file_keys
    value=plan['localRoot']
    local_windows=bool(ntpath.splitdrive(value)[0])
    if local_windows:
        if not ntpath.isabs(value): raise ValueError('Windows source root must be absolute')
        local_display_root=ntpath.normpath(value)
        local_key_root=ntpath.normcase(local_display_root).rstrip(chr(92))
    else:
        if not os.path.isabs(value): raise ValueError('Local source root must be absolute')
        local_display_root=os.path.normpath(value)
        local_key_root=local_display_root.rstrip('/')
    local_file_keys={}
    for relative in plan['files']:
        key=ntpath.normcase(relative).replace(chr(92),'/') if local_windows else relative
        if key in local_file_keys: raise ValueError('Source names are ambiguous on a Windows client: '+relative)
        local_file_keys[key]=relative

def local_relative(path):
    if not isinstance(path,str): return None
    if local_windows:
        if not ntpath.isabs(path) or not ntpath.splitdrive(path)[0]: return None
        value=ntpath.normcase(ntpath.normpath(path));prefix=local_key_root+chr(92)
        suffix=value[len(prefix):].replace(chr(92),'/') if value.startswith(prefix) else None
    else:
        if not os.path.isabs(path): return None
        value=os.path.normpath(path);prefix=local_key_root+'/'
        suffix=value[len(prefix):] if value.startswith(prefix) else None
    return local_file_keys.get(suffix) if suffix is not None else None

def local_source_path(relative):
    if local_windows: return ntpath.join(local_display_root,*relative.split('/'))
    return os.path.join(local_display_root,*relative.split('/'))

def source_path(value):
    if not isinstance(value,dict): return
    path=value.get('path')
    if not isinstance(path,str): return
    remote=os.path.normpath(path)
    prefix=root.rstrip('/')+'/'
    relative=remote[len(prefix):] if remote.startswith(prefix) else None
    # Remote paths remain case-sensitive; only local Windows spellings use a folded lookup.
    if relative not in plan['files']: relative=local_relative(path)
    if relative is not None:
        value['path']=local_source_path(relative)
    else:
        value.pop('path',None);value['sourceReference']=0
        value['name']=(ntpath.basename(path) if local_windows else os.path.basename(path))+' (remote source unavailable)'
        value['origin']='Outside the verified local/remote source mapping'
        value['presentationHint']='deemphasize'

""";
}
