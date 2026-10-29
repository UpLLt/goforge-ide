# Read-only selected-module source capabilities for the owned analysis supervisor.
# This file is embedded in the SSH process; it is not installed on the remote host.
import io, zipfile, struct, contextlib

MODULE_GRAPH_LIMIT = 256
MODULE_ARCHIVE_LIMIT = 16 * 1024 * 1024
MODULE_EXPANDED_LIMIT = 64 * 1024 * 1024
modulefd = None
modulestate = None
moduleinfo = {'error': 'Dependency sources are unavailable for this session'}
module_records = {}
module_verified = {}
modulequeue = queue.Queue(maxsize=16)


def module_check(deadline):
    if stopping.is_set() or time.monotonic() > deadline:
        raise ValueError('Dependency source operation cancelled or timed out')


def module_parts(path):
    items = path.split('/')
    if len(path) > 4096 or len(items) > 64 or any(
            part in ('', '.', '..') or chr(92) in part or
            any(ord(c) < 32 or ord(c) == 127 for c in part) for part in items):
        raise ValueError('Invalid dependency source path')
    return items


def module_escape(value):
    if not value or not re.fullmatch(r'[A-Za-z0-9._~+/-]+', value):
        raise ValueError('Unsupported module identity')
    module_parts(value)
    return ''.join('!' + c.lower() if 'A' <= c <= 'Z' else c for c in value)


def module_version(value):
    if not re.fullmatch(r'v[0-9]+[.][0-9]+[.][0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?(?:[+]incompatible)?', value):
        raise ValueError('Unsupported module version')
    return module_escape(value)


def module_h1(hashes):
    summary = ''.join(hashes[name] + '  ' + name + '\n' for name in sorted(hashes)).encode('utf-8')
    return 'h1:' + base64.b64encode(hashlib.sha256(summary).digest()).decode('ascii')


def module_mod_h1(data):
    return module_h1({'go.mod': hashlib.sha256(data).hexdigest()})


def module_anchors(data):
    if len(data) > 2*1024*1024: raise ValueError('Reviewed go.sum exceeds its bound')
    anchors = {}
    for line in io.StringIO(data.decode('utf-8', 'strict')):
        if not line.strip(): continue
        fields = line.split()
        if len(fields) != 3:
            raise ValueError('Invalid reviewed go.sum record')
        path, version, digest = fields
        module_escape(path)
        module_version(version[:-7] if version.endswith('/go.mod') else version)
        try:
            raw = base64.b64decode(digest[3:], validate=True)
        except Exception:
            raise ValueError('Unsupported reviewed module checksum')
        if not digest.startswith('h1:') or len(raw) != 32 or 'h1:' + base64.b64encode(raw).decode('ascii') != digest:
            raise ValueError('Unsupported reviewed module checksum')
        key = (path, version)
        if key in anchors and anchors[key] != digest:
            raise ValueError('Conflicting reviewed module checksums')
        anchors[key] = digest
        if len(anchors) > 2 * MODULE_GRAPH_LIMIT:
            raise ValueError('Reviewed dependency metadata exceeds its bound')
    return anchors


def module_snapshot_current():
    if not os.path.samestat(os.fstat(rootfd), os.stat(root, follow_symlinks=False)):
        raise ValueError('Reviewed snapshot root was replaced; restart analysis')


def module_remove_private(directory, budget, depth=0):
    if depth > 70: raise ValueError('Private module cleanup depth exceeded; directory retained')
    with os.scandir(directory) as entries:
        for entry in entries:
            budget[0] -= 1
            if budget[0] < 0: raise ValueError('Private module cleanup entry limit exceeded; directory retained')
            info = os.stat(entry.name, dir_fd=directory, follow_symlinks=False)
            if stat.S_ISDIR(info.st_mode):
                child = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                try:
                    if not os.path.samestat(info, os.fstat(child)):
                        raise ValueError('Private module cleanup directory changed; retained')
                    module_remove_private(child, budget, depth+1)
                    if not os.path.samestat(os.fstat(child), os.stat(entry.name, dir_fd=directory, follow_symlinks=False)):
                        raise ValueError('Private module cleanup directory was replaced; retained')
                    os.rmdir(entry.name, dir_fd=directory)
                finally: os.close(child)
            elif stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode):
                os.unlink(entry.name, dir_fd=directory)  # Never follows a symlink.
            else:
                raise ValueError('Unexpected private module file type; directory retained')


@contextlib.contextmanager
def module_private():
    module_snapshot_current()
    leaf = '.goforge-module-' + uuid.uuid4().hex
    os.mkdir(leaf, 0o700, dir_fd=rootfd)
    directory = os.open(leaf, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=rootfd)
    state = {'leaf':leaf, 'fd':directory, 'path':os.path.join(root, leaf)}
    try:
        module_private_current(state)
        yield state
    finally:
        try:
            module_remove_private(directory, [65536])
            if not os.path.samestat(os.fstat(directory), os.stat(leaf, dir_fd=rootfd, follow_symlinks=False)):
                raise ValueError('Private module workspace was replaced; replacement retained')
            os.rmdir(leaf, dir_fd=rootfd)
        finally: os.close(directory)


def module_private_current(state):
    module_snapshot_current()
    if not os.path.samestat(os.fstat(state['fd']), os.stat(state['leaf'], dir_fd=rootfd, follow_symlinks=False)):
        raise ValueError('Private module workspace was replaced; restart analysis')


def module_private_write(state, relative, data):
    items = module_parts(relative); directory = os.dup(state['fd'])
    try:
        for item in items[:-1]:
            try: os.mkdir(item, 0o700, dir_fd=directory)
            except FileExistsError: pass
            child = os.open(item, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory); directory = child
        file = os.open(items[-1], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
        with os.fdopen(file, 'wb') as stream: stream.write(data)
    finally: os.close(directory)


def module_root_current():
    module_snapshot_current()
    current = os.stat(modulestate['cache'], follow_symlinks=False)
    if not stat.S_ISDIR(current.st_mode) or (current.st_dev, current.st_ino) != modulestate['rootIdentity']:
        raise ValueError('Remote module cache root changed; restart analysis')
    for path, digest in metadata_hashes.items():
        if read_owned(path)[0] != digest:
            raise ValueError('Reviewed workspace metadata changed; restart analysis')
    if sdk_identity(os.stat(sdkstate['executable'], follow_symlinks=False)) != sdkstate['goIdentity']:
        raise ValueError('Remote Go executable changed; restart analysis')


def module_walk(items):
    directory = os.dup(modulefd)
    identities = []
    try:
        for item in items:
            nextfd = os.open(item, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = nextfd
            info = os.fstat(directory)
            identities.append((info.st_dev, info.st_ino))
        return directory, identities
    except:
        os.close(directory)
        raise


def module_read(relative, limit, deadline):
    module_check(deadline)
    module_root_current()
    items = module_parts(relative)
    directory, ancestors = module_walk(items[:-1])
    try:
        fd = os.open(items[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        try:
            data, identity = sdk_read_fd(fd, limit)
        finally:
            os.close(fd)
    finally:
        os.close(directory)
    verification, current = module_walk(items[:-1])
    try:
        fd = os.open(items[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=verification)
        try:
            if ancestors != current or sdk_identity(os.fstat(fd)) != identity:
                raise ValueError('Module cache path changed while reading')
        finally:
            os.close(fd)
    finally:
        os.close(verification)
    module_root_current()
    module_check(deadline)
    return data, (ancestors, identity)


def module_path_current(relative, expected_identity):
    items = module_parts(relative)
    directory, ancestors = module_walk(items[:-1])
    try:
        fd = os.open(items[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        try:
            if (ancestors, sdk_identity(os.fstat(fd))) != expected_identity:
                raise ValueError('Verified module cache identity changed during source read')
        finally: os.close(fd)
    finally: os.close(directory)


def module_run(arguments, environment, deadline):
    module_check(deadline)
    process = subprocess.Popen([sdkstate['executable']] + arguments, cwd=root, env=environment,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    values = {}; overflow = threading.Event(); errors = []
    def collect(key, stream, limit):
        try:
            values[key] = stream.read(limit + 1)
            if len(values[key]) > limit: overflow.set()
        except Exception as error: errors.append(error)
    threads = [threading.Thread(target=collect, args=('out', process.stdout, 2*1024*1024), daemon=True),
               threading.Thread(target=collect, args=('err', process.stderr, 8192), daemon=True)]
    for thread in threads: thread.start()
    try:
        while process.poll() is None:
            module_check(deadline)
            if overflow.is_set(): raise ValueError('Module metadata output exceeds its bound')
            time.sleep(.02)
        for thread in threads: thread.join(timeout=.5)
        if process.returncode != 0 or errors or overflow.is_set() or any(t.is_alive() for t in threads):
            raise ValueError('Cached module graph cannot be resolved without downloads')
        return values['out'].decode('utf-8', 'strict')
    finally:
        try: os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError: pass
        process.wait(timeout=2); process.stdout.close(); process.stderr.close()


def module_initialize():
    global modulefd, modulestate, moduleinfo
    if workfile or 'go.mod' not in expected or 'go.sum' not in expected or sum(p.endswith('go.mod') for p in metadata_hashes) != 1:
        raise ValueError('Dependency source lookup currently requires one reviewed root go.mod and go.sum')
    if os.path.lexists(os.path.join(root, 'vendor')):
        raise ValueError('Vendor dependency source lookup is not supported')
    deadline = time.monotonic() + 5
    cache = os.path.realpath(sdkstate['settings']['GOMODCACHE'])
    if not os.path.isabs(cache) or any(ord(c) < 32 for c in cache): raise ValueError('Invalid module cache root')
    modulefd = os.open(cache, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    info = os.fstat(modulefd)
    modulestate = {'cache': cache, 'rootIdentity': (info.st_dev, info.st_ino)}
    env['GOMODCACHE'] = cache
    anchors = module_anchors(read_data('go.sum')[0])
    # Go may write metadata cache locks even with -mod=readonly. Give graph discovery
    # only its own metadata cache and private mod/sum copies; never the real cache.
    with module_private() as private:
        private_mod = os.path.join(private['path'], 'discovery.mod')
        module_private_write(private, 'discovery.mod', read_data('go.mod')[0])
        module_private_write(private, 'discovery.sum', read_data('go.sum')[0])
        private_cache = os.path.join(private['path'], 'cache')
        discovery = dict(env)
        discovery.update({'GOWORK':'off', 'GOFLAGS':'', 'GOMODCACHE':private_cache,
                          'GOPROXY':'off', 'GOSUMDB':'off', 'GOPRIVATE':'', 'GONOPROXY':'', 'GONOSUMDB':'', 'GOVCS':'*:off', 'GOTELEMETRY':'off'})
        module_private_current(private)
        parsed = json.loads(module_run(['mod', 'edit', '-json', private_mod], discovery, deadline))
        module_private_current(private)
        if parsed.get('Replace'): raise ValueError('Replacement dependency source lookup is not supported')
        total = 0
        for (path, version), anchor in anchors.items():
            if not version.endswith('/go.mod'): continue
            version = version[:-7]
            base = 'cache/download/' + module_escape(path) + '/@v/' + module_version(version)
            mod, _ = module_read(base + '.mod', 65536, deadline)
            if module_mod_h1(mod) != anchor: raise ValueError('Cached module metadata differs from reviewed go.sum')
            # Version selection comes from h1-bound go.mod files, not mutable .info
            # timestamps. Historical go.mod-only sums need no cached .info file.
            description = json.dumps({'Version':version}).encode('utf-8')
            total += len(mod) + len(description)
            if total > 16*1024*1024: raise ValueError('Module graph metadata exceeds its bound')
            module_private_write(private, 'cache/' + base + '.mod', mod)
            module_private_write(private, 'cache/' + base + '.info', description)
        discovery['GOFLAGS'] = '-mod=readonly'
        module_private_current(private)
        raw = module_run(['list', '-modfile=' + private_mod, '-m', '-json', 'all'], discovery, deadline)
        module_private_current(private)
        decoder = json.JSONDecoder(); rows = []; seen = set()
        while raw.strip():
            row, offset = decoder.raw_decode(raw.lstrip()); raw = raw.lstrip()[offset:]
            if not isinstance(row, dict) or row.get('Error') or row.get('Replace') or row.get('Path') in seen:
                raise ValueError('Ambiguous or unsupported selected module graph')
            seen.add(row.get('Path')); rows.append(row)
            if len(rows) > MODULE_GRAPH_LIMIT: raise ValueError('Selected module graph exceeds its bound')
        if sum(bool(row.get('Main')) for row in rows) != 1: raise ValueError('Expected one root module')
        for row in rows:
            if row.get('Main'): continue
            path, version = row['Path'], row.get('Version', '')
            escaped = module_escape(path) + '@' + module_version(version)
            content = anchors.get((path, version)); modhash = anchors.get((path, version + '/go.mod'))
            if content is None or modhash is None: continue
            if row.get('Sum') != content or row.get('GoModSum') != modhash:
                raise ValueError('Go selected-module checksums differ from the reviewed snapshot')
            directory, identities = module_walk(module_parts(escaped)); os.close(directory)
            token = hashlib.sha256(os.urandom(32)).hexdigest()
            module_records[token] = {'path':path, 'version':version, 'relativeRoot':escaped,
                'root':cache + '/' + escaped, 'token':token, 'sum':content, 'modsum':modhash,
                'rootIdentities':identities, 'revoked':False}
    module_root_current(); module_check(deadline)
    moduleinfo = {'cacheRoot':cache, 'modules':[{k:row[k] for k in ('path','version','root','token')} for row in module_records.values()]}
    return moduleinfo


def module_zip(data, path, version, deadline):
    # Check the bounded central-directory count before ZipFile materializes entries.
    end = data.rfind(b'PK\x05\x06', max(0, len(data)-65557))
    if end < 0 or end + 22 > len(data): raise ValueError('Invalid module ZIP directory')
    _, disk, startdisk, count, total, size, offset, comment = struct.unpack_from('<4s4H2IH', data, end)
    if disk or startdisk or count != total or total < 1 or total > 4096 or end+22+comment != len(data) or offset+size != end:
        raise ValueError('Unsupported or oversized module ZIP directory')
    prefix = path + '@' + version + '/'; hashes = {}; sizes = {}; folded = set(); expanded = 0
    intervals = []
    with zipfile.ZipFile(io.BytesIO(data), 'r', allowZip64=False) as archive:
        entries = archive.infolist()
        if len(entries) != total: raise ValueError('Module ZIP entry count changed')
        for entry in entries:
            module_check(deadline)
            name = entry.filename
            if name != entry.orig_filename or not name.startswith(prefix): raise ValueError('Module ZIP has a foreign version/path')
            relative = name[len(prefix):]; module_parts(relative)
            mode = (entry.external_attr >> 16) & 0xffff
            if (entry.flag_bits & 1 or entry.compress_type not in (0,8) or entry.extract_version >= 45
                    or entry.is_dir() or mode and stat.S_IFMT(mode) not in (0,stat.S_IFREG)
                    or entry.external_attr & 0x10 or name in hashes or name.casefold() in folded):
                raise ValueError('Unsupported or ambiguous module ZIP entry')
            if entry.file_size < 0 or entry.compress_size < 0 or entry.header_offset < 0 or entry.header_offset >= offset:
                raise ValueError('Invalid module ZIP member boundary')
            header = entry.header_offset
            if header + 30 > offset or data[header:header+4] != b'PK\x03\x04': raise ValueError('Invalid ZIP local header')
            name_length, extra_length = struct.unpack_from('<HH', data, header+26)
            end = header + 30 + name_length + extra_length + entry.compress_size
            if end > offset or any(header < prior_end and end > prior_start for prior_start,prior_end in intervals):
                raise ValueError('Overlapping module ZIP entries')
            intervals.append((header,end))
            extra = entry.extra
            while extra:
                if len(extra) < 4: raise ValueError('Invalid ZIP extra data')
                kind, length = struct.unpack_from('<HH', extra)
                if kind == 1 or length > len(extra)-4: raise ValueError('ZIP64/invalid module ZIP extra data')
                extra = extra[4+length:]
            expanded += entry.file_size
            if expanded > MODULE_EXPANDED_LIMIT: raise ValueError('Expanded module ZIP exceeds its bound')
            digest = hashlib.sha256(); count = 0
            with archive.open(entry) as stream:
                while True:
                    module_check(deadline)
                    chunk = stream.read(65536)
                    if not chunk: break
                    count += len(chunk)
                    if count > entry.file_size: raise ValueError('Module ZIP member exceeds its bound')
                    digest.update(chunk)
            if count != entry.file_size: raise ValueError('Truncated module ZIP entry')
            hashes[name] = digest.hexdigest(); sizes[relative] = count; folded.add(name.casefold())
    return module_h1(hashes), {name[len(prefix):]:digest for name,digest in hashes.items()}, sizes


def module_source(relative, token):
    record = module_records.get(token)
    if record is None or record['revoked']: raise ValueError('Selected module source capability is unavailable')
    deadline = time.monotonic() + 15
    try:
        module_parts(relative)
        if not relative.endswith('.go'): raise ValueError('Only Go dependency source files may be read')
        directory, identities = module_walk(module_parts(record['relativeRoot'])); os.close(directory)
        if identities != record['rootIdentities']: raise ValueError('Selected module directory was replaced')
        base = 'cache/download/' + module_escape(record['path']) + '/@v/' + module_version(record['version'])
        mod, modidentity = module_read(base + '.mod', 65536, deadline)
        if module_mod_h1(mod) != record['modsum']: raise ValueError('Selected module metadata checksum changed')
        data, zipidentity = module_read(base + '.zip', MODULE_ARCHIVE_LIMIT, deadline)
        rawhash = hashlib.sha256(data).hexdigest()
        verified = module_verified.get(token)
        if verified is None:
            if len(module_verified) >= 16: raise ValueError('Verified module archive limit reached')
            content, hashes, sizes = module_zip(data, record['path'], record['version'], deadline)
            if content != record['sum']: raise ValueError('Module archive does not match reviewed go.sum')
            verified = {'zipIdentity':zipidentity, 'modIdentity':modidentity, 'rawhash':rawhash, 'hashes':hashes, 'sizes':sizes}
            module_verified[token] = verified
        elif verified['zipIdentity'] != zipidentity or verified['modIdentity'] != modidentity or verified['rawhash'] != rawhash:
            raise ValueError('Verified module archive/metadata identity changed')
        source, _ = module_read(record['relativeRoot'] + '/' + relative, SDK_FILE_LIMIT, deadline)
        digest = hashlib.sha256(source).hexdigest()
        if digest != verified['hashes'].get(relative) or len(source) != verified['sizes'].get(relative):
            raise ValueError('Cached module source differs from the reviewed module archive')
        text = source.decode('utf-8', 'strict')
        if chr(0) in text: raise ValueError('Module source contains NUL')
        directory, current = module_walk(module_parts(record['relativeRoot'])); os.close(directory)
        if current != identities: raise ValueError('Selected module directory changed during read')
        module_path_current(base + '.zip', zipidentity); module_path_current(base + '.mod', modidentity)
        module_check(deadline); module_root_current()
        return {'path':relative,'token':token,'sha256':digest,'data':base64.b64encode(source).decode('ascii')}
    except:
        record['revoked'] = True
        raise


def module_worker():
    while not stopping.is_set():
        try: request = modulequeue.get(timeout=.1)
        except queue.Empty: continue
        try: response = module_source(request['path'], request['token'])
        except Exception as error: response = {'error':'Remote dependency source could not be read safely: ' + str(error)}
        response['id'] = request['id']
        if not stopping.is_set(): send({'jsonrpc':'2.0','method':'goforge/moduleSource','params':response})
