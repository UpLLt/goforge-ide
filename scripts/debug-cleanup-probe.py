#!/usr/bin/env python3
"""Isolated actual-buffered-socket failure probe; no Windows program is started."""
import importlib.util,json,socket,sys,tempfile,threading
from pathlib import Path
from unittest.mock import patch
module=Path(sys.argv[1]).resolve()
spec=importlib.util.spec_from_file_location('candidate_verifier',module);verify=importlib.util.module_from_spec(spec);spec.loader.exec_module(verify)
wire=verify.load_protocol();original=wire.FramedJson;connections=[];adapters=[]
class InjectedProtocolFailure(RuntimeError):pass
class FailureWire(original):
 def __init__(self,reader,writer):super().__init__(reader,writer);connections.append(self)
 def dap(self,*args,**kwargs):raise InjectedProtocolFailure('deliberate protocol failure with live adapter and blocked reader')
wire.FramedJson=FailureWire
class Adapter:
 def __init__(self,*args,**kwargs):
  self.stopped=False;self.peer=None;self.ready=threading.Event();self.listener=socket.socket();self.listener.bind(('127.0.0.1',0));self.listener.listen(1);adapters.append(self)
  kwargs['stdout'].write('DAP server listening at: 127.0.0.1:'+str(self.listener.getsockname()[1])+'\n');kwargs['stdout'].flush()
  def accept():
   self.peer,_=self.listener.accept();self.ready.set()
  self.thread=threading.Thread(target=accept,daemon=True);self.thread.start()
 def poll(self):return 0 if self.stopped else None
 def stop(self):
  if not self.ready.wait(1):raise AssertionError('fixture server never accepted connection')
  if self.peer:
   try:self.peer.shutdown(socket.SHUT_RDWR)
   except OSError:pass
   self.peer.close()
  self.listener.close();self.thread.join(1);self.stopped=True
with tempfile.TemporaryDirectory(prefix='goforge-buffer-cleanup-') as directory:
 root=Path(directory)
 with patch.object(verify.subprocess,'Popen',Adapter),patch.object(verify,'terminate_owned',lambda adapter:adapter.stop()):
  try:verify.debug(wire,root,root,{},root)
  except InjectedProtocolFailure as error:
   result={'passed':all(a.stopped for a in adapters) and all(c.reader.closed and c.writer.closed for c in connections),'primaryException':type(error).__name__,'message':str(error),'adapterStopped':all(a.stopped for a in adapters),'bufferedStreamsClosed':all(c.reader.closed and c.writer.closed for c in connections),'scope':'Actual local TCP socket and blocked FramedJson reader; fake adapter lifecycle, no Windows native acceptance'}
   print(json.dumps(result));assert result['passed']
  else:raise AssertionError('Expected primary protocol failure')
