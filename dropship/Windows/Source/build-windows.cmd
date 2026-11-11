@echo off
cd /d "%~dp0"
dotnet publish DropShip.Windows\DropShip.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o ..\Build
pause
