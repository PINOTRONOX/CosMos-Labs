using System.Text.Json;
using System.Runtime.InteropServices;
namespace DropShip.Windows;
public sealed class AppSettings {
    public bool WizardComplete{get;set;}=false;
    public string DeviceId{get;set;}=Guid.NewGuid().ToString("N");
    public string DeviceName{get;set;}=Environment.MachineName.Length>40?Environment.MachineName[..40]:Environment.MachineName;
    public string LastDeviceId{get;set;}=""; public string Ip{get;set;}="";public string Port{get;set;}="8765";
    public string ReceiveFolder{get;set;}=Path.Combine(Downloads(),"DropShip");
    public string LastError{get;set;}="";
    public List<SendItem> Queue{get;set;}=[];
    public List<string> History{get;set;}=[];
    static string FilePath=>Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"DropShip","settings-v05.json");
    public static AppSettings Load(){try{return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath))??new();}catch{return new();}}
    public bool Save(){try{History=History.Take(100).ToList();Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);File.WriteAllText(FilePath+".tmp",JsonSerializer.Serialize(this));File.Move(FilePath+".tmp",FilePath,true);return true;}catch{return false;}}
    static string Downloads(){var id=new Guid("374DE290-123F-4565-9164-39C4925E467B");if(SHGetKnownFolderPath(ref id,0,IntPtr.Zero,out var p)==0){try{return Marshal.PtrToStringUni(p)!;}finally{Marshal.FreeCoTaskMem(p);}}return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),"Downloads");}
    [DllImport("shell32.dll")]static extern int SHGetKnownFolderPath(ref Guid id,uint flags,IntPtr token,out IntPtr path);
}

public sealed class SendItem {public string Source{get;set;}="";public string Relative{get;set;}="";public string Id{get;set;}=Guid.NewGuid().ToString("N");public string BatchId{get;set;}=Guid.NewGuid().ToString("N");public bool Directory{get;set;}public string State{get;set;}="대기";public string TargetId{get;set;}="";}
