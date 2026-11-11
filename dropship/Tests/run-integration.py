"""Run portable C# <-> Kotlin transport integration (not Android MediaStore/UI)."""
from pathlib import Path
import argparse, hashlib, json, os, socket, subprocess, sys, time, urllib.parse

p=argparse.ArgumentParser(); p.add_argument('--dotnet',default='dotnet');p.add_argument('--java',default='java');p.add_argument('--work',default='test-work');p.add_argument('--classpath');args=p.parse_args()
root=Path(__file__).resolve().parents[1]; work=Path(args.work).resolve(); fixtures=work/'fixtures'; android=work/'received-android'; pc=work/'received-pc'; changed=work/'new-pc-folder'
for folder in [android,pc,changed]:
 folder.mkdir(parents=True,exist_ok=True)
 if any(folder.iterdir()): raise SystemExit(f'Use an EMPTY test directory: {folder}')
lib=root/'Android/Source/receiver-tests/build/install/receiver-tests/lib'
classpath=args.classpath or os.pathsep.join(str(f) for f in lib.glob('*.jar'))
reports=root/'Tests/Reports'; reports.mkdir(exist_ok=True)
processes=[]; opened=[]; extra=0

def launch(command,path,stdin=False):
 f=open(path,'w');opened.append(f)
 q=subprocess.Popen(command,stdout=f,stderr=subprocess.STDOUT,stdin=subprocess.PIPE if stdin else subprocess.DEVNULL,text=True);processes.append(q);return q

def health(port):
 with socket.create_connection(('127.0.0.1',port),2) as s:
  s.sendall(b'GET /v1/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n');return read(s)

def wait_health(port,process):
 for _ in range(100):
  if process.poll() is not None: raise AssertionError(f'Server exited {process.returncode}')
  try:
   if health(port).startswith(b'HTTP/1.1 200'):return
  except OSError:pass
  time.sleep(.1)
 raise AssertionError('server did not start')

def read(s):
 out=bytearray()
 while True:
  data=s.recv(65536)
  if not data:break
  out.extend(data)
 return bytes(out)

def raw(port,name,body=b'',length=None,sha=None,extraheaders='',cut=False):
 with socket.create_connection(('127.0.0.1',port),3) as s:
  s.settimeout(8)
  head=f'POST /v1/files HTTP/1.1\r\nHost: localhost\r\nContent-Length: {len(body) if length is None else length}\r\nX-DropShip-Filename: {urllib.parse.quote(name,safe="")}\r\nX-DropShip-SHA256: {sha or hashlib.sha256(body).hexdigest()}\r\n{extraheaders}Connection: close\r\n\r\n'
  s.sendall(head.encode('ascii')+body)
  if cut:s.shutdown(socket.SHUT_WR)
  return read(s)

def passed(label):
 global extra
 extra+=1; print('PASS '+label,flush=True)

def check(condition,label):
 if not condition:raise AssertionError(label)
 passed(label)

try:
 ar=launch([args.java,'-cp',classpath,'ReceiverHarnessKt',str(android)],work/'kotlin-receiver.log'); wait_health(18765,ar)
 result=subprocess.run([args.dotnet,str(root/'Tests/SenderTests/bin/Debug/net8.0/SenderTests.dll'),str(fixtures),str(android)],capture_output=True,text=True,timeout=180)
 (reports/'PC_TO_ANDROID.txt').write_text(result.stdout+'\n'+result.stderr)
 print(result.stdout,flush=True)
 if result.returncode:raise AssertionError('C# -> Kotlin tests failed: '+result.stderr)
 pr=launch([args.dotnet,str(root/'Tests/ReceiverTests/bin/Debug/net8.0/ReceiverTests.dll'),str(pc)],work/'csharp-receiver.log',True); wait_health(18767,pr)
 result=subprocess.run([args.java,'-cp',classpath,'SenderHarnessKt',str(fixtures),str(pc)],capture_output=True,text=True,timeout=180)
 (reports/'ANDROID_TO_PC.txt').write_text(result.stdout+'\n'+result.stderr)
 print(result.stdout,flush=True)
 if result.returncode:raise AssertionError('Kotlin -> C# tests failed: '+result.stderr)

 body=b'file integrity'; zeros='0'*64
 check(raw(18767,'bad-hash.bin',body,sha=zeros).startswith(b'HTTP/1.1 422'),'C# receiver rejects hash mismatch')
 check(not (pc/'bad-hash.bin').exists(),'bad hash not published')
 check('연결' in json.loads(raw(18767,'cut.bin',b'10',length=1000,cut=True).split(b'\r\n\r\n',1)[1])['error'],'C# interrupted upload Korean error')
 time.sleep(.1)
 check(not list(pc.glob('.dropship-partial-*')),'C# incomplete files cleaned')
 for name in ['../escape.txt','CON.txt','LPT1.bin','trailing.','trailing ','sub\\escape.txt']:
  check(raw(18767,name).startswith(b'HTTP/1.1 400'),'C# invalid filename '+name)
 check(raw(18767,'no-space.bin',length=2**63-1).startswith(b'HTTP/1.1 507'),'C# preflight storage shortage (declared-size simulation)')
 check(not (pc/'no-space.bin').exists(),'storage-shortage file not published')
 check(raw(18767,'duplicate-header.bin',extraheaders='Content-Length: 0\r\n').startswith(b'HTTP/1.1 400'),'duplicate header rejected')
 check(raw(18767,'chunked.bin',extraheaders='Transfer-Encoding: chunked\r\n').startswith(b'HTTP/1.1 400'),'chunked request rejected')
 with socket.create_connection(('127.0.0.1',18767),3) as first:
  first.sendall(('POST /v1/files HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000\r\nX-DropShip-Filename: busy.bin\r\nX-DropShip-SHA256: '+zeros+'\r\n\r\n1').encode())
  for _ in range(50):
   if list(pc.glob('.dropship-partial-*')):break
   time.sleep(.02)
  check(health(18767).startswith(b'HTTP/1.1 200'),'health works during active receive')
  check(raw(18767,'second.bin').startswith(b'HTTP/1.1 409'),'second active upload rejected')
  first.shutdown(socket.SHUT_WR);read(first)
 time.sleep(.1)
 check(not list(pc.glob('.dropship-partial-*')),'busy interrupted upload cleaned')
 # Receiver stop must abort incomplete uploads and make the port reusable.
 holding=socket.create_connection(('127.0.0.1',18767),3)
 holding.sendall(('POST /v1/files HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1000\r\nX-DropShip-Filename: stop.bin\r\nX-DropShip-SHA256: '+zeros+'\r\n\r\n1').encode())
 for _ in range(50):
  if list(pc.glob('.dropship-partial-*')):break
  time.sleep(.02)
 pr.stdin.write('STOP\n');pr.stdin.flush()
 holding.settimeout(5);read(holding);holding.close()
 for _ in range(50):
  if not list(pc.glob('.dropship-partial-*')):break
  time.sleep(.02)
 check(not list(pc.glob('.dropship-partial-*')) and not (pc/'stop.bin').exists(),'stop receiver drains and cleans active partial')
 pr.stdin.write('START '+str(changed)+'\n');pr.stdin.flush();wait_health(18767,pr)
 check(raw(18767,'changed.txt',b'new folder').startswith(b'HTTP/1.1 201'),'restart receiver same port in new folder')
 check((changed/'changed.txt').read_bytes()==b'new folder' and not (pc/'changed.txt').exists(),'new receive folder routing')
 # Simulate a collision introduced between preflight and final publication.
 content=b'collision original'; name='race.txt'
 with socket.create_connection(('127.0.0.1',18767),3) as s:
  header=f'POST /v1/files HTTP/1.1\r\nHost: localhost\r\nContent-Length: {len(content)}\r\nX-DropShip-Filename: {name}\r\nX-DropShip-SHA256: {hashlib.sha256(content).hexdigest()}\r\n\r\n'
  s.sendall(header.encode()+content[:1]);time.sleep(.05);(changed/name).write_bytes(b'keep me');s.sendall(content[1:]);response=read(s)
 check(response.startswith(b'HTTP/1.1 201') and (changed/'race.txt').read_bytes()==b'keep me' and (changed/'race (1).txt').read_bytes()==content,'atomic collision handling preserves late existing file')
 check(health(18767).startswith(b'HTTP/1.1 200'),'PC receiver healthy after errors and restart')
 check(health(18765).startswith(b'HTTP/1.1 200'),'Android protocol receiver healthy after original regression')
 (reports/'RECEIVER_ERRORS_LIFECYCLE.txt').write_text(f'{extra} additional portable receiver/error/lifecycle checks passed\n')
 print(f'EXTRA {extra} passed',flush=True)
 pr.stdin.write('EXIT\n');pr.stdin.flush();pr.wait(timeout=10)
finally:
 for q in processes:
  if q.poll() is None:q.terminate()
 for q in processes:
  try:q.wait(timeout=5)
  except subprocess.TimeoutExpired:q.kill()
 for f in opened:f.close()
