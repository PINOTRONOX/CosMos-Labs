"""Real C# <-> JVM UDP discovery over loopback; physical Wi-Fi must be checked separately."""
import argparse,subprocess,os,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--dotnet',default='dotnet');p.add_argument('--java',default='java');p.add_argument('--classpath');a=p.parse_args()
r=Path(__file__).resolve().parents[1];cp=a.classpath or os.pathsep.join(str(x) for x in (r/'Android/Source/receiver-tests/build/install/receiver-tests/lib').glob('*.jar'));dll=str(r/'Tests/DiscoveryTests/bin/Debug/net8.0/DiscoveryTests.dll')
cs=[a.dotnet,dll];kt=[a.java,'-cp',cp,'DiscoveryHarnessKt'];servers=[]
try:
 for cmd in [cs+['respond'],kt+['respond']]:
  proc=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);servers.append(proc)
  if proc.stdout.readline().strip()!='READY':raise RuntimeError('discovery responder failed')
 for cmd in [cs+['unit'],kt+['unit'],cs+['scan'],kt+['scan'],cs+['scan']]:
  result=subprocess.run(cmd,capture_output=True,text=True,timeout=15);print(result.stdout,end='',flush=True)
  if result.returncode:raise RuntimeError(result.stderr)
 print('PASS repeated scan deduplicates device responses')
finally:
 for proc in servers:proc.terminate()
 for proc in servers:
  try:proc.wait(timeout=5)
  except subprocess.TimeoutExpired:proc.kill()
