"""Developer fixtures: pip install pillow. The ZIP contains incompressible bytes."""
from pathlib import Path
from PIL import Image
import os, zipfile, hashlib, json, sys
p=Path(sys.argv[1]); p.mkdir(parents=True,exist_ok=True)
(p/'01_test.txt').write_bytes((b'DropShip test text\r\n'*60)[:1024].ljust(1024,b' '))
Image.frombytes('RGB',(1600,1200),os.urandom(1600*1200*3)).save(p/'02_test.jpg',quality=90)
with (p/'02_test.jpg').open('ab') as f:f.write(b'\0'*(5*1024*1024-f.tell()))
# A valid PDF containing legal comment padding INSIDE its content stream, with correct xref.
content=b'BT /F1 24 Tf 50 700 Td (DropShip PDF integrity test) Tj ET\n'+(b'%'+b'x'*1022+b'\n')*10240
objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',b'<< /Length '+str(len(content)).encode()+b' >>\nstream\n'+content+b'endstream']
with (p/'03_test.pdf').open('wb') as f:
 f.write(b'%PDF-1.4\n'); offsets=[]
 for i,obj in enumerate(objects,1):
  offsets.append(f.tell()); f.write(f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n')
 xref=f.tell(); f.write(b'xref\n0 6\n0000000000 65535 f \n')
 for o in offsets:f.write(f'{o:010d} 00000 n \n'.encode())
 f.write(f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
with zipfile.ZipFile(p/'04_test.zip','w',compression=zipfile.ZIP_STORED) as z:
 with z.open('payload.bin','w',force_zip64=True) as f:
  for _ in range(100):f.write(os.urandom(1024*1024))
print(json.dumps([{ 'name':f.name,'size':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest() } for f in sorted(p.iterdir())],indent=2))
