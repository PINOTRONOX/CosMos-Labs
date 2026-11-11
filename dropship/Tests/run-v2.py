"""Real TCP pairing / authenticated chunk transfer / folders / durable resume, C# <-> JVM.
The JVM storage adapter is a filesystem adapter; SAF, native UI, Shell and Android shares require devices.
"""
from pathlib import Path
import argparse,subprocess,socket,json,hashlib,os,time,sys,uuid
p=argparse.ArgumentParser();p.add_argument('--dotnet',default='dotnet');p.add_argument('--java',default='java');p.add_argument('--work',default='v06-test-work');a=p.parse_args()
r=Path(__file__).resolve().parents[1];w=Path(a.work).resolve();w.mkdir(exist_ok=True);fixtures=w/'fixtures';fixtures.mkdir(exist_ok=True)
small=fixtures/'한글 + 공백.txt';small.write_bytes('라이의 드랍쉽 v0.6 ✓'.encode());large=fixtures/'100mb.bin'
if not large.exists():
 with large.open('wb') as f:
  for _ in range(100):f.write(os.urandom(1048576))
empty=fixtures/'empty.bin';empty.write_bytes(b'')
csid='1'*32;ktid='2'*32;statecs=w/'cs';statekt=w/'kt';receivecs=w/'receive-cs';receivekt=w/'receive-kt'
for d in [statecs,statekt,receivecs,receivekt]:
 d.mkdir(exist_ok=True)
 if any(d.iterdir()):raise SystemExit("Use an empty test workspace: "+str(d))
cp=os.pathsep.join(str(x) for x in (r/'Android/Source/receiver-tests/build/install/receiver-tests/lib').glob('*.jar'))
cs=[a.dotnet,str(r/'Tests/V2Tests/bin/Debug/net8.0/V2Tests.dll')];kt=[a.java,'-cp',cp,'V2HarnessKt'];servers=[];passed=0

def check(ok,label):
 global passed
 if not ok:raise AssertionError(label)
 passed+=1;print('PASS '+label,flush=True)
def launch(base,state,own,receive,port):
 q=subprocess.Popen([*base,'server',str(state),own,str(receive),str(port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);servers.append(q)
 if q.stdout.readline().strip()!='READY':raise RuntimeError(q.stderr.read())
 return q
def cmd(q,line,expected):q.stdin.write(line+'\n');q.stdin.flush();check(q.stdout.readline().strip()==expected,'server '+line.split()[0])
def end(q):q.stdin.write('EXIT\n');q.stdin.flush();q.wait(timeout=10);servers.remove(q)
def request(port,method,path,value=None,headers=None,body=None):
 data=body if body is not None else json.dumps(value,ensure_ascii=False).encode() if value is not None else b''
 with socket.create_connection(('127.0.0.1',port),3) as s:
  s.settimeout(15);h=f'{method} {path} HTTP/1.1\r\nHost: localhost\r\nContent-Length: {len(data)}\r\nConnection: close\r\n';h+=''.join(f'{k}: {v}\r\n' for k,v in (headers or {}).items());s.sendall(h.encode()+b'\r\n'+data);out=b''
  while True:
   b=s.recv(65536)
   if not b:break
   out+=b
 head,raw=out.split(b'\r\n\r\n',1);return int(head.split(b' ')[1]),json.loads(raw)
def auth(state,own,target):
 d=next(x for x in json.loads((state/'devices.json').read_text()) if x['id']==target)
 return {'X-DropShip-Device':own,'X-DropShip-Target':target,'X-DropShip-Token':d['token']}
def pair(base,state,own,port,q):
 proc=subprocess.Popen([*base,'pair',str(state),own,str(port)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 code=proc.stdout.readline().strip();check(code.startswith('CODE ') and len(code.split()[1])==6,'requester shows six digit code')
 cmd(q,'APPROVE','APPROVED');out,err=proc.communicate(timeout=15);check(proc.returncode==0 and 'PAIRED' in out,'approval completes real client '+base[-1])
def send(base,state,own,port,target,file,relative,tid,batch,mode='send',success=True):
 result=subprocess.run([*base,mode,str(state),own,str(port),target,str(file),relative,tid,batch],capture_output=True,text=True,timeout=180)
 if success and result.returncode:raise RuntimeError(result.stdout+result.stderr)
 return result
def sid():return uuid.uuid4().hex
def readiness(q,base,state,own,target,port,receive,incoming,h):
 cmd(q,'FOLDER_OFF','FOLDER_OFF')
 status,health=request(port,'GET','/v2/health')
 check(status==200 and health['receiveReady']==False and '폴더' in health['receiveMessage'],'health explains missing receive folder')
 before=set(incoming.rglob('*'));tid=sid()
 result=send(base,state,own,port,target,fixtures/'DOES_NOT_EXIST','blocked.txt',tid,sid(),success=False)
 check(result.returncode!=0 and '폴더' in result.stderr and '409' in result.stderr,'client readiness fails before opening nonexistent source')
 check(set(incoming.rglob('*'))==before and not (receive/'blocked.txt').exists(),'preflight creates no partial session or output')
 spec={'transferId':tid,'batchId':sid(),'relativePath':'blocked.txt','size':0,'sha256':hashlib.sha256(b'').hexdigest(),'directory':False}
 code,error=request(port,'POST','/v2/transfers',spec,h)
 check(code==409 and '폴더' in error['error'],'authenticated direct create rejected before storage')
 check(set(incoming.rglob('*'))==before,'direct readiness failure leaves no partial data')
 cmd(q,'FOLDER_ON','FOLDER_ON')
 check(request(port,'GET','/v2/health')[1]['receiveReady'],'folder restored readiness')
 send(base,state,own,port,target,small,'folder-ready.txt',tid,sid())
 check((receive/'folder-ready.txt').read_bytes()==small.read_bytes(),'send succeeds after folder setup')
 result=send(base,state,own,port,'f'*32,fixtures/'DOES_NOT_EXIST','wrong-target.txt',sid(),sid(),success=False)
 check(result.returncode!=0 and '403' in result.stderr,'wrong selected identity blocked before source open')
try:
 q=launch(kt,statekt,ktid,receivekt,18865)
 check(request(18865,'GET','/v2/health')[1]['approvalRequired'],'receiver advertises approval requirement')
 spec={'transferId':sid(),'batchId':sid(),'relativePath':'unauthorized.txt','size':0,'sha256':hashlib.sha256(b'').hexdigest(),'directory':False}
 check(request(18865,'POST','/v2/transfers',spec)[0]==403,'unapproved create blocked')
 check(not list(receivekt.iterdir()),'unapproved request creates no received file')
 pair(cs,statecs,csid,18865,q);check(json.loads((statecs/'devices.json').read_text())[0]['token']==json.loads((statekt/'devices.json').read_text())[0]['token'],'both devices store same paired credential')
 h=auth(statecs,csid,ktid);readiness(q,cs,statecs,csid,ktid,18865,receivekt,statekt/"incoming",h);wrong=dict(h);wrong['X-DropShip-Token']='0'*64
 check(request(18865,'POST','/v2/transfers',spec,wrong)[0]==403,'wrong token blocked')
 wrong=dict(h);wrong['X-DropShip-Target']=csid;check(request(18865,'POST','/v2/transfers',spec,wrong)[0]==403,'wrong target identity blocked')
 for name in ['../escape.txt','C:/escape.txt','root/../escape.txt','root\\escape.txt','CON.txt','root//empty']:
  v=dict(spec,transferId=sid(),relativePath=name);check(request(18865,'POST','/v2/transfers',v,h)[0]==400,'unsafe relative path blocked '+name)
 tid=sid();batch=sid();out=send(cs,statecs,csid,18865,ktid,large,'100mb.bin',tid,batch,'cancel');check('CANCELLED' in out.stdout,'C# mid-transfer cancel')
 status=request(18865,'GET','/v2/transfers/'+tid,headers=h)[1];check(status['offset']==1048576 and not status['complete'],'Kotlin durably holds confirmed 1 MiB')
 check(not (receivekt/'100mb.bin').exists(),'partial not published')
 # Interrupted body cannot add an unverified partial chunk.
 with socket.create_connection(('127.0.0.1',18865),3) as s:
  head=f'PATCH /v2/transfers/{tid} HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1048576\r\nX-DropShip-Offset: 1048576\r\nX-DropShip-Chunk-SHA256: '+('0'*64)+'\r\n'+''.join(f'{k}: {v}\r\n' for k,v in h.items())+'\r\n';s.sendall(head.encode()+b'abc');s.shutdown(socket.SHUT_WR)
  while s.recv(65536):pass
 check(request(18865,'GET','/v2/transfers/'+tid,headers=h)[1]['offset']==1048576,'cut chunk preserves previous confirmed offset')
 end(q);q=launch(kt,statekt,ktid,receivekt,18865);out=send(cs,statecs,csid,18865,ktid,large,'100mb.bin',tid,batch);check('RESUME 1048576' in out.stdout,'C# resume after receiver process restart')
 check(hashlib.sha256((receivekt/'100mb.bin').read_bytes()).digest()==hashlib.sha256(large.read_bytes()).digest(),'100 MB C# -> Kotlin stored bytes/hash')
 out=send(cs,statecs,csid,18865,ktid,large,'100mb.bin',tid,batch);check(not (receivekt/'100mb (1).bin').exists(),'completed session retry is idempotent (lost receipt safe)')
 for path,file in [('한글 + 공백.txt',small),('empty.bin',empty)]:send(cs,statecs,csid,18865,ktid,file,path,sid(),sid());check((receivekt/path).read_bytes()==file.read_bytes(),'C# Unicode/zero bytes '+path)
 send(cs,statecs,csid,18865,ktid,small,small.name,sid(),sid());check((receivekt/'한글 + 공백 (1).txt').exists(),'new selection uses duplicate suffix and preserves original')
 folderbatch=sid();(receivekt/'사진').mkdir();(receivekt/'사진'/'keep.txt').write_text('keep')
 for path,isdir,file in [('사진',True,empty),('사진/빈 폴더',True,empty),('사진/하위',True,empty),('사진/하위/문서.txt',False,small)]:send(cs,statecs,csid,18865,ktid,file,path,sid(),folderbatch,'directory' if isdir else 'send')
 check((receivekt/'사진 (1)'/'빈 폴더').is_dir(),'nested empty folder preserved')
 check((receivekt/'사진 (1)'/'하위'/'문서.txt').read_bytes()==small.read_bytes() and (receivekt/'사진'/'keep.txt').read_text()=='keep','folder collision maps whole batch without modifying original')
 corrupt=sid();send(cs,statecs,csid,18865,ktid,large,'corrupt.bin',corrupt,sid(),'cancel');payload=next((statekt/'incoming').rglob(corrupt+'.data'))
 with payload.open('r+b') as f:f.write(b'broken')
 # Complete remaining bytes using the exact old metadata.
 manifest=json.loads(next((statekt/'incoming').rglob(corrupt+'.json')).read_text());batch=manifest['spec']['batchId']
 result=send(cs,statecs,csid,18865,ktid,large,'corrupt.bin',corrupt,batch,success=False);check(result.returncode!=0,'corrupted stored prefix fails final verification')
 check(request(18865,'GET','/v2/transfers/'+corrupt,headers=h)[1]['offset']==0,'bad prefix resets resumable offset')
 send(cs,statecs,csid,18865,ktid,large,'corrupt.bin',corrupt,batch);check(V:=hashlib.sha256((receivekt/'corrupt.bin').read_bytes()).digest()==hashlib.sha256(large.read_bytes()).digest(),'retry after bad-prefix reset succeeds')
 # HTTP pairing nonce and ownership checks.
 requestId=request(18865,'POST','/v2/pair/request',{'deviceId':'3'*32,'name':'other','nonce':'b'*32})[1]['requestId'];check(request(18865,'GET','/v2/pair/'+requestId,headers={'X-DropShip-Nonce':'c'*32})[0]==404,'pair approval token cannot be polled with wrong nonce');cmd(q,'REJECT','REJECTED');check(request(18865,'GET','/v2/pair/'+requestId,headers={'X-DropShip-Nonce':'b'*32})[1]['status']=='rejected','pair rejection retained')
 end(q)
 # Reverse direction uses the symmetric paired device book persisted by first pairing.
 q=launch(cs,statecs,csid,receivecs,18867);h=auth(statekt,ktid,csid)
 readiness(q,kt,statekt,ktid,csid,18867,receivecs,statecs/"incoming",h)
 tid=sid();batch=sid();out=send(kt,statekt,ktid,18867,csid,large,'reverse.bin',tid,batch,'cancel');check('CANCELLED' in out.stdout,'Kotlin mid-transfer cancel')
 check(request(18867,'GET','/v2/transfers/'+tid,headers=h)[1]['offset']==1048576,'C# stores confirmed resumable chunk')
 cmd(q,'STOP','STOPPED');cmd(q,'START','READY');out=send(kt,statekt,ktid,18867,csid,large,'reverse.bin',tid,batch);check('RESUME 1048576' in out.stdout,'Kotlin resumes after C# receiver restart')
 check(hashlib.sha256((receivecs/'reverse.bin').read_bytes()).digest()==hashlib.sha256(large.read_bytes()).digest(),'100 MB Kotlin -> C# stored bytes/hash')
 for path,file in [('한글 + 공백.txt',small),('empty.bin',empty)]:send(kt,statekt,ktid,18867,csid,file,path,sid(),sid());check((receivecs/path).read_bytes()==file.read_bytes(),'Kotlin Unicode/zero bytes '+path)
 batch=sid()
 for path,isdir,file in [('역방향',True,empty),('역방향/빈 폴더',True,empty),('역방향/자료.txt',False,small)]:send(kt,statekt,ktid,18867,csid,file,path,sid(),batch,'directory' if isdir else 'send')
 check((receivecs/'역방향'/'빈 폴더').is_dir() and (receivecs/'역방향'/'자료.txt').read_bytes()==small.read_bytes(),'Kotlin folder tree -> C#')
 badspec=dict(spec,transferId=sid(),relativePath='wrong-chunk.txt',size=3,sha256=hashlib.sha256(b'abc').hexdigest());request(18867,'POST','/v2/transfers',badspec,h);badheaders=dict(h,**{'X-DropShip-Offset':'0','X-DropShip-Chunk-SHA256':'0'*64});check(request(18867,'PATCH','/v2/transfers/'+badspec['transferId'],headers=badheaders,body=b'abc')[0]==422,'C# rejects wrong chunk hash')
 badheaders['X-DropShip-Offset']='1';badheaders['X-DropShip-Chunk-SHA256']=hashlib.sha256(b'abc').hexdigest();check(request(18867,'PATCH','/v2/transfers/'+badspec['transferId'],headers=badheaders,body=b'abc')[0]==409,'C# rejects wrong resume offset')
 check(request(18867,'POST','/v2/transfers/'+badspec['transferId']+'/commit',{},h)[0]==409,'incomplete commit rejected')
 changed=dict(badspec,size=4);check(request(18867,'POST','/v2/transfers',changed,h)[0]==409,'changed source specification rejected')
 cmd(q,'REVOKE '+ktid,'REVOKED');check(request(18867,'GET','/v2/transfers/'+tid,headers=h)[0]==403,'revoked device cannot access completed or partial session')
 end(q);q=launch(cs,statecs,csid,receivecs,18867);check(request(18867,'POST','/v2/transfers',spec,h)[0]==403,'revocation survives receiver process restart')
 check(request(18867,'POST','/v1/files',{},h)[0]==403,'legacy unauthenticated upload path remains blocked')
 # Reverse-direction pairing and C# storage corruption recovery.
 pair(kt,statekt,ktid,18867,q);h=auth(statekt,ktid,csid)
 check(request(18867,'POST','/v1/files',{},h)[0]==404,'authenticated legacy upload route also rejected')
 other=request(18867,'POST','/v2/pair/request',{'deviceId':'3'*32,'name':'third device','nonce':'d'*32})[1];cmd(q,'APPROVE','APPROVED')
 otherToken=request(18867,'GET','/v2/pair/'+other['requestId'],headers={'X-DropShip-Nonce':'d'*32})[1]['token'];otherH={'X-DropShip-Device':'3'*32,'X-DropShip-Target':csid,'X-DropShip-Token':otherToken}
 check(request(18867,'GET','/v2/transfers/'+tid,headers=otherH)[0]==403,'another approved device cannot read session')
 short=fixtures/'4mb.bin';short.write_bytes(os.urandom(4*1048576));corrupt=sid();batch=sid();send(kt,statekt,ktid,18867,csid,short,'bad-prefix.bin',corrupt,batch,'cancel')
 payload=next((statecs/'incoming').rglob(corrupt+'.data'))
 with payload.open('r+b') as f:f.write(b'broken')
 result=send(kt,statekt,ktid,18867,csid,short,'bad-prefix.bin',corrupt,batch,success=False)
 check(result.returncode!=0 and request(18867,'GET','/v2/transfers/'+corrupt,headers=h)[1]['offset']==0,'C# corrupted prefix fails and resets')
 send(kt,statekt,ktid,18867,csid,short,'bad-prefix.bin',corrupt,batch);check((receivecs/'bad-prefix.bin').read_bytes()==short.read_bytes(),'C# storage corruption recovery')
 outside=w/'outside.txt';outside.write_bytes(b'keep');(receivecs/'linked.txt').symlink_to(outside)
 result=send(kt,statekt,ktid,18867,csid,small,'linked.txt',sid(),sid(),success=False);check(result.returncode!=0 and outside.read_bytes()==b'keep','C# rejects symlink destination and preserves outside file')
 check(request(18867,'POST','/v2/pair/request',{'deviceId':'3'*32,'name':'third','nonce':'bad'})[0]==400,'malformed pairing nonce rejected')
 race=sid();batch=sid();send(kt,statekt,ktid,18867,csid,short,'race.txt',race,batch,'cancel');payload=next((statecs/'incoming').rglob(race+'.data'));journal=Path(str(payload)+'.publish.json');journal.write_text(json.dumps(str(receivecs/'race.txt')));(receivecs/'race.txt').write_bytes(b'keep')
 send(kt,statekt,ktid,18867,csid,short,'race.txt',race,batch);check((receivecs/'race.txt').read_bytes()==b'keep' and (receivecs/'race (1).txt').read_bytes()==short.read_bytes(),'late publication-name collision preserves original and retries with suffix')
 clean=sid();batch=sid();send(kt,statekt,ktid,18867,csid,short,'clean.bin',clean,batch,'cancel');cmd(q,'CLEAN','CLEANED');check(request(18867,'GET','/v2/transfers/'+clean,headers=h)[0]==404,'local cleanup deletes unfinished session')
 out=send(kt,statekt,ktid,18867,csid,short,'clean.bin',clean,batch);check('RESUME' not in out.stdout and (receivecs/'clean.bin').read_bytes()==short.read_bytes(),'cleared work restarts safely from zero')
 # Two sockets cannot append the same range twice.
 import concurrent.futures
 concurrentId=sid();spec2=dict(spec,transferId=concurrentId,relativePath='concurrent.txt',size=3,sha256=hashlib.sha256(b'abc').hexdigest());request(18867,'POST','/v2/transfers',spec2,h)
 hh=dict(h,**{'X-DropShip-Offset':'0','X-DropShip-Chunk-SHA256':hashlib.sha256(b'abc').hexdigest()})
 with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda _:request(18867,'PATCH','/v2/transfers/'+concurrentId,headers=hh,body=b'abc')[0],range(2)))
 check(sorted(results)==[200,409],'duplicate concurrent chunk accepts one range only')
 check(request(18867,'POST','/v2/transfers/'+concurrentId+'/commit',{},h)[0]==201 and (receivecs/'concurrent.txt').read_bytes()==b'abc','concurrent chunk result remains exact')
 print(f'TOTAL {passed} passed',flush=True)
finally:
 for q in servers:
  q.terminate()
  try:q.wait(timeout=5)
  except subprocess.TimeoutExpired:q.kill()
