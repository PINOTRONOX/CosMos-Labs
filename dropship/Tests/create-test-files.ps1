# Run with Windows PowerShell: right-click > Run with PowerShell.
$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([Environment]::GetFolderPath('Desktop')) 'DropShip_TestFiles'
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$names = @('01_test.txt','02_test.jpg','03_test.pdf','04_test.zip')
foreach ($name in $names) { if (Test-Path (Join-Path $testRoot $name)) { throw "Already exists: $name. Rename or move the old test folder first." } }
[IO.File]::WriteAllBytes((Join-Path $testRoot '01_test.txt'), [Text.Encoding]::UTF8.GetBytes(('DropShip text test. ' * 60).Substring(0,1024)))
Add-Type -AssemblyName System.Drawing
$bmp = New-Object Drawing.Bitmap 1280,720
$graphics = [Drawing.Graphics]::FromImage($bmp)
$font = New-Object Drawing.Font 'Arial',42
try {
    $graphics.Clear([Drawing.Color]::LightSeaGreen)
    $graphics.DrawString('DropShip JPG test', $font, [Drawing.Brushes]::White, 80, 300)
    $bmp.Save((Join-Path $testRoot '02_test.jpg'), [Drawing.Imaging.ImageFormat]::Jpeg)
} finally { $font.Dispose(); $graphics.Dispose(); $bmp.Dispose() }
$stream = [IO.File]::Open((Join-Path $testRoot '02_test.jpg'), [IO.FileMode]::Open, [IO.FileAccess]::Write)
try { $stream.SetLength(5MB) } finally { $stream.Dispose() }
$stream = [IO.File]::Create((Join-Path $testRoot '03_test.pdf'))
$offsets = New-Object 'System.Collections.Generic.List[long]'
function WriteAscii([string]$text) { $bytes = [Text.Encoding]::ASCII.GetBytes($text); $stream.Write($bytes,0,$bytes.Length) }
function PdfObject([int]$id,[string]$text) { $offsets.Add($stream.Position); WriteAscii "$id 0 obj`n$text`nendobj`n" }
try {
    WriteAscii "%PDF-1.4`n"
    PdfObject 1 '<< /Type /Catalog /Pages 2 0 R >>'
    PdfObject 2 '<< /Type /Pages /Kids [3 0 R] /Count 1 >>'
    PdfObject 3 '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>'
    PdfObject 4 '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
    $content = "BT /F1 24 Tf 50 700 Td (DropShip PDF test) Tj ET`n"
    $padding = '%' + ('x' * 1022) + "`n"
    $repeat = 10240
    $length = [Text.Encoding]::ASCII.GetByteCount($content) + 1024 * $repeat
    $offsets.Add($stream.Position)
    WriteAscii "5 0 obj`n<< /Length $length >>`nstream`n$content"
    for ($i=0;$i -lt $repeat;$i++) { WriteAscii $padding }
    WriteAscii "endstream`nendobj`n"
    $xref = $stream.Position
    WriteAscii "xref`n0 6`n0000000000 65535 f `n"
    foreach ($offset in $offsets) { WriteAscii (($offset.ToString('0000000000')) + " 00000 n `n") }
    WriteAscii "trailer`n<< /Size 6 /Root 1 0 R >>`nstartxref`n$xref`n%%EOF`n"
} finally { $stream.Dispose() }
Add-Type -AssemblyName System.IO.Compression
$stream = [IO.File]::Create((Join-Path $testRoot '04_test.zip'))
$zip = New-Object IO.Compression.ZipArchive $stream, ([IO.Compression.ZipArchiveMode]::Create), $false
try {
    $entry = $zip.CreateEntry('payload.bin', [IO.Compression.CompressionLevel]::NoCompression)
    $entryStream = $entry.Open()
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    $buffer = New-Object byte[] 1048576
    try { for ($i=0;$i -lt 100;$i++) { $random.GetBytes($buffer); $entryStream.Write($buffer,0,$buffer.Length) } }
    finally { $random.Dispose(); $entryStream.Dispose() }
} finally { $zip.Dispose(); $stream.Dispose() }
Get-ChildItem $testRoot | ForEach-Object { Get-FileHash -Algorithm SHA256 $_.FullName } | Format-Table -AutoSize
Write-Host "Created test files in $testRoot"
Read-Host 'Press Enter to close'
