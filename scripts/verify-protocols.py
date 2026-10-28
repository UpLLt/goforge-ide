#!/usr/bin/env python3
"""Exercise real local gopls and Delve protocols against disposable fixtures.

These are protocol checks, not proof of IDE UI integration or GoLand parity.
No installed GoLand file or user project is modified.
"""
from pathlib import Path
from collections import Counter, deque
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
BIN = ROOT / '.tools/bin'
OUT = ROOT / '.artifacts'
OUT.mkdir(exist_ok=True)
ENV = {**os.environ, 'PATH': str(BIN) + os.pathsep + os.environ.get('PATH', '')}


class FramedJson:
    def __init__(self, reader, writer):
        self.reader, self.writer = reader, writer
        self.messages = queue.Queue()
        self.notifications = []
        self.seq = 0
        self._dap_started = time.monotonic()
        self._dap_lock = threading.Lock()
        self._dap_trace = deque(maxlen=100)
        self._dap_event_counts = Counter()
        self._stream_error = None
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        try:
            while True:
                headers = {}
                while True:
                    line = self.reader.readline()
                    if not line:
                        raise EOFError('Protocol stream ended')
                    if line in (b'\r\n', b'\n'):
                        break
                    key, value = line.decode('ascii').split(':', 1)
                    headers[key.lower()] = value.strip()
                remaining = int(headers['content-length'])
                chunks = []
                while remaining:
                    chunk = self.reader.read(remaining)
                    if not chunk:
                        raise EOFError('Incomplete protocol message')
                    chunks.append(chunk)
                    remaining -= len(chunk)
                message = json.loads(b''.join(chunks))
                self._record_dap(message, 'received')
                self.messages.put(message)
        except Exception as exc:
            with self._dap_lock:
                self._stream_error = f'{type(exc).__name__}: {exc}'
            self.messages.put(exc)

    def _record_dap(self, message, direction):
        if message.get('type') not in ('request', 'response', 'event'):
            return
        summary = {'direction': direction, 'elapsed_seconds': round(time.monotonic() - self._dap_started, 3)}
        for key in ('type', 'seq', 'request_seq', 'command', 'event', 'success', 'message'):
            if key in message:
                summary[key] = message[key]
        body = message.get('body') or {}
        if message.get('type') == 'event':
            for key in ('category', 'reason', 'threadId', 'exitCode', 'systemProcessId'):
                if key in body:
                    summary[key] = body[key]
            if isinstance(body.get('output'), str):
                summary['output_excerpt'] = body['output'][:500]
        with self._dap_lock:
            self._dap_trace.append(summary)
            if direction == 'received' and message.get('type') == 'event':
                self._dap_event_counts[message.get('event', '<unnamed>')] += 1

    def dap_diagnostics(self):
        with self._dap_lock:
            return {'received_event_counts': dict(self._dap_event_counts),
                    'recent_messages': [dict(item) for item in self._dap_trace],
                    'stream_error': self._stream_error}

    def send(self, message):
        body = json.dumps(message).encode()
        self.writer.write(f'Content-Length: {len(body)}\r\n\r\n'.encode() + body)
        self.writer.flush()
        self._record_dap(message, 'sent')

    def receive_until(self, predicate, timeout=40):
        for index, item in enumerate(self.notifications):
            if predicate(item):
                return self.notifications.pop(index)
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Timed out waiting for protocol response')
            try:
                item = self.messages.get(timeout=remaining)
            except queue.Empty as exc:
                raise TimeoutError('Timed out waiting for protocol response') from exc
            if isinstance(item, Exception):
                raise item
            if predicate(item):
                return item
            self.notifications.append(item)
            # gopls may make supported client-side requests during startup.
            if 'method' in item and 'id' in item:
                self.send({'jsonrpc': '2.0', 'id': item['id'], 'result': None})

    def lsp(self, method, params=None):
        self.seq += 1
        request_id = self.seq
        self.send({'jsonrpc': '2.0', 'id': request_id, 'method': method, 'params': params})
        message = self.receive_until(lambda x: x.get('id') == request_id and 'method' not in x)
        if 'error' in message:
            raise RuntimeError(f'{method}: {message["error"]}')
        return message.get('result')

    def notify(self, method, params=None):
        self.send({'jsonrpc': '2.0', 'method': method, 'params': params})

    def dap(self, command, arguments=None):
        self.seq += 1
        request_id = self.seq
        started = time.monotonic()
        self.send({'seq': request_id, 'type': 'request', 'command': command, 'arguments': arguments or {}})
        try:
            response = self.receive_until(lambda x: x.get('type') == 'response' and x.get('request_seq') == request_id)
        except TimeoutError as exc:
            events = self.dap_diagnostics()['received_event_counts']
            raise TimeoutError(f'DAP {command} response (request_seq={request_id}) timed out after '
                               f'{time.monotonic() - started:.2f}s; received events: {events}') from exc
        if not response.get('success'):
            raise RuntimeError(f'{command}: {response.get("message", response)}')
        return response.get('body', {})


def terminate(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def run_lsp():
    with tempfile.TemporaryDirectory(prefix='goforge-lsp-', dir=OUT) as tmp:
        project = Path(tmp)
        shutil.copytree(ROOT / 'examples/hello-go', project, dirs_exist_ok=True)
        source = project / 'main.go'
        original = source.read_text()
        uri = source.as_uri()
        log = (OUT / 'gopls-smoke.stderr.log').open('w')
        proc = subprocess.Popen([str(BIN / 'gopls'), 'serve'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=log, cwd=project, env=ENV)
        rpc = FramedJson(proc.stdout, proc.stdin)
        try:
            capabilities = rpc.lsp('initialize', {
                'processId': os.getpid(), 'rootUri': project.as_uri(),
                'workspaceFolders': [{'uri': project.as_uri(), 'name': 'goforge-smoke'}],
                'capabilities': {'textDocument': {'completion': {'completionItem': {'snippetSupport': True}}}},
            })['capabilities']
            assert capabilities.get('definitionProvider') and capabilities.get('renameProvider'), 'Missing semantic capabilities'
            rpc.notify('initialized', {})
            rpc.notify('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'go', 'version': 1, 'text': original}})
            lines = original.splitlines()
            call_line = next(i for i, x in enumerate(lines) if 'greeting.Hello(' in x)
            position = {'line': call_line, 'character': lines[call_line].index('Hello') + 1}
            location = {'textDocument': {'uri': uri}, 'position': position}
            definitions = rpc.lsp('textDocument/definition', location)
            definitions = definitions if isinstance(definitions, list) else [definitions]
            assert any((x.get('uri') or x.get('targetUri', '')).endswith('/greeting/greeting.go') for x in definitions), definitions
            rename = rpc.lsp('textDocument/rename', {**location, 'newName': 'Welcome'})
            edits = rename.get('documentChanges', []) or rename.get('changes', {})
            assert len(edits) >= 2, 'Rename did not cover definition and call sites'
            completion = rpc.lsp('textDocument/completion', {
                **location, 'position': {'line': call_line, 'character': lines[call_line].index('Hello')},
                'context': {'triggerKind': 1},
            })
            items = completion if isinstance(completion, list) else completion.get('items', [])
            assert any('Hello' in item.get('label', '') for item in items), 'Expected Go symbol completion'
            broken = original.replace('greeting.Hello("GoForge")', 'greeting.MissingSymbol("GoForge")')
            rpc.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': 2}, 'contentChanges': [{'text': broken}]})
            diagnostics = rpc.receive_until(lambda x: x.get('method') == 'textDocument/publishDiagnostics'
                                           and x.get('params', {}).get('uri') == uri
                                           and any('MissingSymbol' in d.get('message', '') for d in x['params'].get('diagnostics', [])))
            rpc.lsp('shutdown')
            rpc.notify('exit')
            proc.wait(timeout=10)
            return {'passed': True, 'checks': ['initialize', 'cross-file definition', 'multi-file rename edit',
                                             'symbol completion', 'unsaved-buffer diagnostics', 'shutdown'],
                    'diagnostic_count': len(diagnostics['params']['diagnostics'])}
        finally:
            terminate(proc)
            log.close()


def run_dap():
    log_path = OUT / 'delve-smoke.log'
    with log_path.open('w+') as log:
        proc = subprocess.Popen([str(BIN / 'dlv'), 'dap', '--listen=127.0.0.1:0'], stdout=log, stderr=log, env=ENV)
        connection = None
        try:
            deadline = time.monotonic() + 10
            address = None
            while time.monotonic() < deadline:
                content = log_path.read_text()
                match = re.search(r'DAP server listening at: (127\.0\.0\.1):(\d+)', content)
                if match:
                    address = (match[1], int(match[2]))
                    break
                if proc.poll() is not None:
                    raise RuntimeError(content)
                time.sleep(0.05)
            if not address:
                raise TimeoutError('Delve did not advertise its loopback endpoint')
            connection = socket.create_connection(address, timeout=10)
            connection.settimeout(None)
            reader, writer = connection.makefile('rb'), connection.makefile('wb')
            rpc = FramedJson(reader, writer)
            capabilities = rpc.dap('initialize', {'clientID': 'goforge-smoke', 'adapterID': 'go',
                                                 'pathFormat': 'path', 'linesStartAt1': True,
                                                 'columnsStartAt1': True})
            assert capabilities.get('supportsConfigurationDoneRequest'), capabilities
            rpc.dap('disconnect', {'terminateDebuggee': False})
            proc.wait(timeout=10)
            reader.close()
            writer.close()
            return {'passed': True, 'checks': ['loopback DAP endpoint', 'initialize capabilities', 'disconnect'],
                    'not_tested': ['debuggee launch', 'breakpoints', 'step', 'variables', 'IDE debugger UI']}
        finally:
            if connection:
                connection.close()
            terminate(proc)


if __name__ == '__main__':
    result = {'scope': 'real local protocol smoke tests; not IDE acceptance or GoLand parity', 'lsp': None, 'dap': None}
    try:
        result['lsp'] = run_lsp()
        result['dap'] = run_dap()
    finally:
        (OUT / 'protocol-checks.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
