using DropShip.Core;
using QRCoder;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
namespace DropShip.Windows;
public sealed partial class MainForm {
    TabPage wizardTab=null!;
    readonly PictureBox wizardQr=new(){Width=280,Height=280,SizeMode=PictureBoxSizeMode.Zoom,BackColor=Color.White};
    readonly ComboBox wizardIps=new(){Width=280,DropDownStyle=ComboBoxStyle.DropDownList};
    readonly Label wizardState=new(){AutoSize=true,MaximumSize=new Size(730,0)};
    readonly Label qrNote=new(){AutoSize=true,MaximumSize=new Size(730,0)};
    long qrExpiry;bool wizardTesting;
    TabPage BuildWizard(){
        wizardTab=new("✦ 처음 연결 도우미"){Padding=new(18),AutoScroll=true};
        var panel=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true};wizardTab.Controls.Add(panel);
        panel.Controls.Add(Label("드로피와 첫 배송을 시작해요!",21,true));
        panel.Controls.Add(Label("01  PC와 휴대폰 준비",13,true));
        panel.Controls.Add(Label("PC의 EXE와 휴대폰의 APK를 각각 실행하고 같은 Wi-Fi에 연결해 주세요.\n휴대폰에서 받을 폴더를 선택하고 앱과 화면을 켜 두세요.\nAPK 설치 승인과 권한 허용은 휴대폰에서 직접 진행합니다."));
        panel.Controls.Add(Label("02  휴대폰으로 QR 스캔",13,true));
        panel.Controls.Add(Label("휴대폰의 [처음 연결 도우미] → [PC QR 스캔]을 누르세요.\n주소가 여러 개면 휴대폰과 같은 Wi-Fi의 PC 주소를 선택하세요."));
        panel.Controls.Add(wizardIps);panel.Controls.Add(wizardQr);panel.Controls.Add(qrNote);
        var renew=new CuteButton{Text="주소 확인 / QR 새로 만들기",AutoSize=true};panel.Controls.Add(renew);
        renew.Click+=(_,_)=>RefreshWizardAddresses();wizardIps.SelectedIndexChanged+=(_,_)=>MakeWizardQr();
        panel.Controls.Add(Label("03  양쪽 화면의 확인 코드 비교 후 승인",13,true));
        panel.Controls.Add(Label("QR은 PC 주소 입력용이며 자동 승인하지 않습니다.\n휴대폰에서 연결을 요청하고 PC에서 같은 코드를 확인해 승인하세요."));
        panel.Controls.Add(Label("04  실제 파일로 연결 확인",13,true));
        panel.Controls.Add(Label("아래에서 등록된 휴대폰을 선택하고 테스트하세요.\n작은 DropShip-연결테스트 TXT가 휴대폰의 받을 폴더에 저장됩니다."));
        var choices=new ComboBox{Width=520,DropDownStyle=ComboBoxStyle.DropDownList};panel.Controls.Add(choices);
        var list=new CuteButton{Text="등록된 기기 새로 확인",AutoSize=true};panel.Controls.Add(list);
        void Populate(){choices.Items.Clear();foreach(var d in book.Devices)choices.Items.Add(new Choice(d.Id,d.Name,directory.Display(d.Id,d.Name)));if(choices.Items.Count>0)choices.SelectedIndex=0;}
        list.Click+=(_,_)=>Populate();
        var test=new CuteButton{Text="테스트 파일 보내기",AutoSize=true};panel.Controls.Add(test);panel.Controls.Add(wizardState);
        test.Click+=async(_,_)=>{if(wizardTesting||batch!=null||current!=null||selecting)return;if(choices.SelectedItem is not Choice c){wizardState.Text="먼저 휴대폰에서 연결을 요청하고 PC에서 승인한 뒤 목록을 새로 확인하세요.";return;}
            wizardTesting=true;test.Enabled=false;selecting=true;SetBusy(true);string temp="";
            using var ct=CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);ct.CancelAfter(TimeSpan.FromSeconds(30));
            try{wizardState.Text="휴대폰 주소 확인 및 테스트 파일 전송 중…";var found=await DiscoveryScanner.ScanAsync(settings.DeviceId,ct.Token);var ep=found.FirstOrDefault(x=>x.Id==c.Id)?.Endpoint??directory.Hint(c.Id)??throw new IOException("휴대폰을 찾지 못했습니다. 휴대폰 앱과 Wi-Fi를 확인하세요.");var remote=await client.Inspect(ep,ct.Token);if(remote.Id!=c.Id)throw new IOException("다른 기기가 응답했습니다. 다시 연결하세요.");if(!remote.ReceiveReady)throw new IOException(remote.ReceiveMessage);temp=Path.Combine(Path.GetTempPath(),"DropShip-wizard-"+Guid.NewGuid()+".txt");await File.WriteAllTextAsync(temp,"DropShip 0.7.1 연결 테스트 성공!\n",ct.Token);var receipt=await client.Send(ep,c.Id,temp,"DropShip-연결테스트-"+DateTime.Now.ToString("yyyyMMdd-HHmmss")+".txt",Guid.NewGuid().ToString("N"),Guid.NewGuid().ToString("N"),false,new Progress<TransferProgress>(),ct.Token);settings.WizardComplete=true;settings.LastDeviceId=c.Id;settings.Save();wizardState.Text="✓ 첫 배송 성공! 휴대폰 저장 및 SHA-256 검증을 확인했습니다.\n보내기 탭에서 파일을 추가해 이용하세요.";AddHistory("도우미 · 테스트 성공",receipt.Name,receipt.Size);
            }catch(Exception e){if(!closing)wizardState.Text="테스트 실패 · "+e.Message+"\n휴대폰 앱을 켜고 받을 폴더·같은 Wi-Fi·VPN·공유기 격리를 확인하세요. 방화벽 차단도 원인일 수 있습니다.";}
            finally{if(temp!="")try{File.Delete(temp);}catch{}wizardTesting=false;selecting=false;if(!closing){test.Enabled=true;SetBusy(false);}}
        };
        panel.Controls.Add(Label("연결이 안 되나요?",13,true));
        panel.Controls.Add(Label("검색 실패만으로 원인을 확정할 수는 없어요.\n① 양쪽 앱을 켜고 같은 Wi-Fi인지 확인  ② QR 주소를 바꿔 다시 스캔\n③ 휴대폰 수동 IP 입력 사용  ④ VPN·게스트 Wi-Fi·공유기 기기 격리 확인\n⑤ Windows 방화벽에서 DropShip의 신뢰하는 개인 네트워크 통신 허용 확인\n방화벽 전체를 끄지 마세요. 전송은 암호화되지 않으므로 신뢰하는 네트워크에서 사용하세요."));
        wizardTab.Enter+=(_,_)=>{RefreshWizardAddresses();Populate();UpdateWizard();};return wizardTab;
    }
    void RefreshWizardAddresses(){var old=wizardIps.SelectedItem?.ToString();wizardIps.Items.Clear();foreach(var n in NetworkInterface.GetAllNetworkInterfaces().Where(n=>n.OperationalStatus==OperationalStatus.Up).OrderBy(n=>n.NetworkInterfaceType==NetworkInterfaceType.Wireless80211?0:1))foreach(var a in n.GetIPProperties().UnicastAddresses.Where(a=>a.Address.AddressFamily==AddressFamily.InterNetwork&&!IPAddress.IsLoopback(a.Address))){try{DeviceEndpoint.Parse(a.Address.ToString(),"8765");wizardIps.Items.Add(a.Address.ToString());}catch{}}if(wizardIps.Items.Count>0){wizardIps.SelectedIndex=old!=null&&wizardIps.Items.Contains(old)?wizardIps.Items.IndexOf(old):0;}else{wizardQr.Image?.Dispose();wizardQr.Image=null;qrNote.Text="로컬 IPv4 주소가 없습니다. Wi-Fi를 연결한 뒤 다시 확인하세요.";}}
    void MakeWizardQr(){if(wizardIps.SelectedItem is not string addr)return;qrExpiry=DateTimeOffset.UtcNow.AddMinutes(10).ToUnixTimeSeconds();var payload=$"dropship://connect?v=1&ip={addr}&port=8765&id={settings.DeviceId}&exp={qrExpiry}";using var data=QRCodeGenerator.GenerateQrCode(payload,QRCodeGenerator.ECCLevel.M);using var png=new PngByteQRCode(data);using var stream=new MemoryStream(png.GetGraphic(8));using var image=Image.FromStream(stream);var old=wizardQr.Image;wizardQr.Image=new Bitmap(image);old?.Dispose();qrNote.Text=$"PC 주소 {addr}:8765 · QR 유효 시간 10분\nQR에는 주소와 기기 ID만 담기며 등록 승인 코드·비밀번호는 담지 않습니다.";}
    void UpdateWizard(){if(qrExpiry>0&&DateTimeOffset.UtcNow.ToUnixTimeSeconds()>qrExpiry){wizardQr.Image?.Dispose();wizardQr.Image=null;qrExpiry=0;qrNote.Text="QR 유효 시간이 지났어요. [QR 새로 만들기]를 눌러주세요.";}if(!wizardTesting&&wizardState.Text=="")wizardState.Text=receiver==null?"PC 수신 서버가 실행되지 않았습니다. 받기 탭에서 수신 시작을 확인하세요.":"PC 수신 서버 실행 중 · 기기 등록 후 실제 전송을 테스트하세요.";}
}
