using DropShip.Core;
string root = Path.GetFullPath(args[0]);
ReceiverServer? server = null;
void Start(string target) {
    server = new ReceiverServer(target, 18767);
    server.Progress += p => { if (p.Name.StartsWith("mid-cancel-") && p.Bytes == 0) Thread.Sleep(500); };
    server.Completed += r => Console.WriteLine($"SAVED {r.Name} {r.Size}");
    server.Failed += m => Console.WriteLine("FAILED " + m);
    server.Start(); Console.WriteLine("READY 18767 " + target);
}
Start(root);
while (true) {
    var command = await Task.Run(() => Console.ReadLine());
    if (command is null || command == "EXIT") break;
    if (command == "STOP") { await server!.DisposeAsync(); server = null; Console.WriteLine("STOPPED"); }
    else if (command.StartsWith("START ") && server is null) { Start(Path.GetFullPath(command[6..])); }
}
if (server is not null) await server.DisposeAsync();
