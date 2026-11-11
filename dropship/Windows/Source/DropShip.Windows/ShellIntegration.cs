using System.IO.Pipes;
using System.Text.Json;
using System.Security.Principal;
using Microsoft.Win32;
namespace DropShip.Windows;
public static class ShellIntegration {
    public static string PipeName=>"DropShipV05-"+WindowsIdentity.GetCurrent().User!.Value;
    public static void Install(){var directory=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"DropShip","v05");Directory.CreateDirectory(directory);var target=Path.Combine(directory,"DropShip.exe");var source=Environment.ProcessPath!;if(!string.Equals(source,target,StringComparison.OrdinalIgnoreCase))File.Copy(source,target,true);
        foreach(var kind in new[]{"*","Directory"}){using var key=Registry.CurrentUser.CreateSubKey($@"Software\Classes\{kind}\shell\DropShipV05");key.SetValue("","DropShip으로 보내기");key.SetValue("Icon",target);key.SetValue("MultiSelectModel","Document");using var command=key.CreateSubKey("command");command.SetValue("",$"\"{target}\" --enqueue \"%1\"");}}
    public static void Remove(){foreach(var kind in new[]{"*","Directory"})Registry.CurrentUser.DeleteSubKeyTree($@"Software\Classes\{kind}\shell\DropShipV05",false);}
    public static async Task Forward(string[] paths){using var pipe=new NamedPipeClientStream(".",PipeName,PipeDirection.Out,PipeOptions.Asynchronous|PipeOptions.CurrentUserOnly);await pipe.ConnectAsync(5000);var bytes=JsonSerializer.SerializeToUtf8Bytes(paths);if(bytes.Length>131072)throw new IOException("선택한 경로가 너무 많습니다.");await pipe.WriteAsync(BitConverter.GetBytes(bytes.Length));await pipe.WriteAsync(bytes);await pipe.FlushAsync();}
    public static async Task Listen(Action<string[]> receive,CancellationToken ct){while(!ct.IsCancellationRequested){try{using var pipe=new NamedPipeServerStream(PipeName,PipeDirection.In,1,PipeTransmissionMode.Byte,PipeOptions.Asynchronous|PipeOptions.CurrentUserOnly);await pipe.WaitForConnectionAsync(ct);using var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct);timeout.CancelAfter(TimeSpan.FromSeconds(5));var head=new byte[4];await pipe.ReadExactlyAsync(head,timeout.Token);int n=BitConverter.ToInt32(head);if(n<0||n>131072)continue;var bytes=new byte[n];await pipe.ReadExactlyAsync(bytes,timeout.Token);var paths=JsonSerializer.Deserialize<string[]>(bytes);if(paths!=null&&paths.Length<=1000)receive(paths);}catch(OperationCanceledException)when(ct.IsCancellationRequested){break;}catch{}}}
}
