package com.cosrai.dropship
import java.net.URI

data class ConnectionQr(val endpoint:DeviceEndpoint,val id:String){
    companion object{
        fun parse(text:String,now:Long=System.currentTimeMillis()/1000):ConnectionQr{
            require(text.length in 1..512){"드랍쉽 PC 도우미의 QR코드를 스캔하세요."}
            val u=URI(text)
            require(u.scheme=="dropship"&&u.rawAuthority=="connect"&&u.rawPath.isNullOrEmpty()&&u.rawFragment==null){"드랍쉽 연결용 QR이 아닙니다."}
            val values=linkedMapOf<String,String>()
            for(part in (u.rawQuery?:"").split('&')){val pair=part.split('=',limit=2);require(pair.size==2&&!values.containsKey(pair[0])){"QR 형식이 올바르지 않습니다."};values[pair[0]]=pair[1]}
            require(values.keys==setOf("v","ip","port","id","exp")&&values["v"]=="1"){"지원하지 않는 QR 형식입니다."}
            val id=values["id"]!!;require(id.matches(Regex("[a-f0-9]{32}"))){"기기 ID가 올바르지 않습니다."}
            val expiry=values["exp"]?.toLongOrNull()?:0
            require(expiry>now&&expiry<=now+660){"QR 유효 시간이 지났거나 기기 시각이 다릅니다. 시각을 확인한 뒤 PC에서 QR을 새로 만드세요."}
            val ep=DeviceEndpoint.parse(values["ip"]!!,values["port"]!!)
            require(!ep.ip.startsWith("127.")){"같은 Wi-Fi의 PC 주소를 사용하세요."}
            return ConnectionQr(ep,id)
        }
    }
}
