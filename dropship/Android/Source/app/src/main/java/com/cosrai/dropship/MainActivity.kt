package com.cosrai.dropship

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.io.*
import java.net.*
import java.util.Locale

class MainActivity : Activity() {
    private var helper:android.app.AlertDialog?=null
    private var helperState:TextView?=null
    private var helperAutoShown=false
    private val main = Handler(Looper.getMainLooper())
    private val teal = Color.rgb(109, 79, 196)
    private val ink = Color.rgb(52, 43, 78)
    private val prefs by lazy { getSharedPreferences("dropship-v05", MODE_PRIVATE) }
    private lateinit var ip: EditText
    private lateinit var port: EditText
    private lateinit var sendState: TextView
    private lateinit var selection: TextView
    private lateinit var sendBar: ProgressBar
    private lateinit var choose: Button
    private lateinit var check: Button
    private lateinit var send: Button
    private lateinit var cancel: Button
    private lateinit var receiveState: TextView
    private lateinit var address: TextView
    private lateinit var current: TextView
    private lateinit var history: TextView
    private lateinit var receiveBar: ProgressBar
    private lateinit var sendCard: LinearLayout
    private lateinit var receiveCard: LinearLayout
    private lateinit var sendTab:Button
    private lateinit var receiveTab:Button
    private class Selection(val uri:Uri,val name:String,val size:Long,val id:String=V2Json.id(),val batch:String=V2Json.id(),val directory:Boolean=false) { @Volatile var state="?湲?;val cancelled=AtomicBoolean(false);var snapshot="";var target="" }
    private val book by lazy{PairBook(File(filesDir,"v05-devices.json"),deviceId,{deviceName})}
    private var service:V2Service?=null
    private val directory by lazy{DeviceDirectory(File(filesDir,"v06-device-notes.json"))}
    private lateinit var targetLabel:TextView
    private lateinit var sendRegisteredRows:LinearLayout
    private val discovered=linkedMapOf<String,Peer>()
    private var selectedId=""
    private var applyingEndpoint=false
    private var folderPromptShown=false
    private var setupDialog:android.app.AlertDialog?=null
    @Volatile private var lastError=""
    private lateinit var registeredRows:LinearLayout
    private lateinit var receiveFolder:TextView
    private lateinit var pairButton:Button
    private val notified=mutableSetOf<String>()
    private var approvalDialog:android.app.AlertDialog?=null
    private var shownTicket=""
    private val outbox by lazy{File(filesDir,"outbox").apply{mkdirs()}}
    private val queueFile by lazy{File(filesDir,"v05-queue.json")}
    private fun saveQueue():Boolean{try{val list=JSONArray();selections.forEach{list.put(JSONObject().put("uri",it.uri.toString()).put("name",it.name).put("size",it.size).put("id",it.id).put("batch",it.batch).put("directory",it.directory).put("state",it.state).put("snapshot",it.snapshot).put("target",it.target))};V2Json.write(queueFile,list.toString());return true}catch(_:Exception){return false}}
    private fun restoreQueue(){try{val a=JSONArray(queueFile.readText());for(i in 0 until minOf(1000,a.length())){val j=a.getJSONObject(i);if(!V2Json.hex(j.getString("id"),32)||!V2Json.hex(j.getString("batch"),32))continue;V2Json.path(j.getString("name"));val entry=Selection(Uri.parse(j.getString("uri")),j.getString("name"),j.getLong("size"),j.getString("id"),j.getString("batch"),j.getBoolean("directory"));entry.snapshot=j.optString("snapshot");entry.target=j.optString("target");entry.state=if(j.getString("state") in listOf("?꾨즺 쨌 寃利??쇱튂","?쒖쇅"))j.getString("state") else "以묐떒 쨌 ?댁뼱蹂대궡湲?;selections.add(entry)}}catch(_:Exception){}}
    private val selections=mutableListOf<Selection>()
    private lateinit var queueRows:LinearLayout
    private lateinit var peerRows:LinearLayout
    private lateinit var scanButton:Button
    private lateinit var myName:EditText
    private var discovery:DiscoveryResponder?=null
    private var multicastLock:android.net.wifi.WifiManager.MulticastLock?=null
    private var scanCancel:AtomicBoolean?=null
    @Volatile private var batchRunning=false
    private val stopBatch=AtomicBoolean(false)
    private val deviceId by lazy { prefs.getString("deviceId",null) ?: UUID.randomUUID().toString().replace("-","").also{prefs.edit().putString("deviceId",it).apply()} }
    @Volatile private var deviceName="Android"
    @Volatile private var currentSelection:Selection?=null
    private var receiver: ReceiverV2? = null
    @Volatile private var operation: SendControl? = null
    private var active = false
    private var cleaned = false
    private var epoch = 0
    private val recent = ArrayDeque<String>()
    private val refresh = object : Runnable { override fun run() { if (active) { updateAddress();checkPairRequests();promptReceiveFolder(); main.postDelayed(this, 3000) } } }
    private val decoder = object : ResponseDecoder {
        override fun health(json: String): Boolean { val j = JSONObject(json); return j.optString("service") == "DropShip" && j.optInt("protocol") == 1 }
        override fun receipt(json: String): SendReceipt { val j = JSONObject(json); return SendReceipt(j.getString("name"), j.getLong("size"), j.getString("sha256"), j.getString("path")) }
        override fun error(json: String) = JSONObject(json).optString("error", "PC?먯꽌 ?뚯씪??諛쏆? 紐삵뻽?듬땲??")
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun label(text: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(ink); setPadding(0, dp(6), 0, dp(8)); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(text: String, primary: Boolean = false) = Button(this).apply {
        this.text=text;isAllCaps=false;textSize=14f;minHeight=dp(50);setPadding(dp(14),dp(10),dp(14),dp(10));setTypeface(typeface,Typeface.BOLD)
        DropTheme.style(this,primary,text.contains("痍⑥냼")||text.contains("?댁젣"))
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6);bottomMargin=dp(4)}
    }
    private fun showPage(sending:Boolean){sendCard.visibility=if(sending)View.VISIBLE else View.GONE;receiveCard.visibility=if(sending)View.GONE else View.VISIBLE;DropTheme.style(sendTab,sending);DropTheme.style(receiveTab,!sending);sendTab.isSelected=sending;receiveTab.isSelected=!sending}
    private fun arrangeSendCard(){
        val old=(0 until sendCard.childCount).map{sendCard.getChildAt(it)}
        fun oldButton(text:String)=old.filterIsInstance<Button>().first{it.text.toString()==text}
        val folderButton=oldButton("?대뜑 異붽?");val clear=oldButton("?꾨즺쨌?쒖쇅 ??ぉ ?뺣━");val error=oldButton("留덉?留??ㅻ쪟 蹂듭궗")
        sendCard.removeAllViews()
        fun add(v:View){sendCard.addView(v)}
        fun step(text:String){add(label(text,16f,true).apply{setTextColor(teal);setPadding(0,dp(18),0,dp(8))})}
        add(label("?대뵒濡?蹂대궡蹂쇨퉴??",21f,true));add(label("媛숈? Wi-Fi?먯꽌 ?곷? ?깆쓣 耳?二쇱꽭??",13f))
        step("01  諛쏆쓣 湲곌린 怨좊Ⅴ湲?)
        targetLabel.textSize=16f;targetLabel.background=DropTheme.shape(this,Color.rgb(225,247,239));targetLabel.setPadding(dp(14),dp(14),dp(14),dp(14));add(targetLabel)
        add(sendRegisteredRows);add(scanButton);add(peerRows);add(pairButton)
        val toggle=button("?섎룞 IP濡??곌껐  ??);val manual=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        manual.addView(label("寃?됱씠 ???????곷? 湲곌린??IP瑜??낅젰?섏꽭??",12f));manual.addView(label("?섎룞 IP"));manual.addView(ip);manual.addView(label("Port"));manual.addView(port);manual.addView(check)
        toggle.setOnClickListener{val open=manual.visibility!=View.VISIBLE;manual.visibility=if(open)View.VISIBLE else View.GONE;toggle.text=if(open)"?섎룞 IP ?묎린  ?? else "?섎룞 IP濡??곌껐  ??};add(toggle);add(manual)
        step("02  蹂대궪 ?뚯씪 ?닿린");add(selection);choose.text="竊? ?щ윭 ?뚯씪 異붽?";folderButton.text="竊? ?대뜑 異붽?";add(choose);add(folderButton);add(queueRows);add(clear)
        step("03  以鍮꾨릱?ㅻ㈃ 異쒕컻!");send.text="?? 蹂대궡湲?/ ?댁뼱蹂대궡湲?;add(send);add(cancel);add(sendBar);add(sendState);add(error)
        receiveState.background=DropTheme.shape(this,Color.rgb(225,247,239));receiveState.setPadding(dp(14),dp(14),dp(14),dp(14))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(18))
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.WHITE);setStroke(dp(1),Color.rgb(235,229,245)) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }
    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(teal)
        layoutParams = LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(10); bottomMargin = dp(8) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(20)); setBackgroundColor(Color.rgb(248, 246, 255)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(16) + insets.systemWindowInsetLeft, dp(16) + insets.systemWindowInsetTop, dp(16) + insets.systemWindowInsetRight, dp(20) + insets.systemWindowInsetBottom)
            insets
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        val hero=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        val words=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};words.addView(label("DropShip",30f,true));words.addView(label("?뚯씪???ｊ퀬, ?щ퓧!",14f,true));words.addView(label("v0.7.1 쨌 ?곕━ 湲곌린 ?ъ씠 ?묒? 諛곗넚",11f))
        hero.addView(words,LinearLayout.LayoutParams(0,-2,1f));hero.addView(ImageView(this).apply{setImageResource(com.cosrai.dropship.R.drawable.dropship_icon);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(92),dp(92)));root.addView(hero)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sendTab = button("??蹂대궡湲?); receiveTab = button("??諛쏄린")
        tabs.addView(sendTab, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }); tabs.addView(receiveTab, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(button("??泥섏쓬 ?곌껐 ?꾩슦誘?).apply{setOnClickListener{showHelper()}});root.addView(tabs)
        sendCard = card(); receiveCard = card(); root.addView(sendCard); root.addView(receiveCard)
        fun show(sending: Boolean) { showPage(sending) }
        sendTab.setOnClickListener { show(true) }; receiveTab.setOnClickListener { show(false) }; show(!prefs.getString("receiveTree","").isNullOrEmpty())
        sendCard.addView(label("?좏깮??湲곌린濡?蹂대궡湲?, 21f, true)); sendCard.addView(label("諛쏅뒗 湲곌린???깆쓣 耳쒓퀬 ?대쫫???좏깮?섏꽭?? 寃?됱씠 ???섎㈃ IP瑜??낅젰?섏꽭??",13f))
        selectedId=prefs.getString("lastDeviceId","")?:"";lastError=prefs.getString("lastError","")?:""
        targetLabel=label("諛쏅뒗 湲곌린: ?좏깮??二쇱꽭??",19f,true);sendCard.addView(targetLabel)
        sendCard.addView(label("?깅줉??湲곌린 쨌 ?꾨Ⅴ硫??꾩넚 ??곸쑝濡??좏깮",16f,true))
        sendRegisteredRows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};sendCard.addView(sendRegisteredRows)
        scanButton=button("湲곌린 李얘린");sendCard.addView(scanButton);scanButton.setOnClickListener{scanDevices()}
        peerRows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};sendCard.addView(peerRows)
        sendCard.addView(label("?섎룞 IP")); ip = EditText(this).apply { setSingleLine(); hint = "?? 192.168.0.25"; inputType = android.text.InputType.TYPE_CLASS_PHONE; setText(prefs.getString("pcIp", "")); setTextColor(ink) }; sendCard.addView(ip)
        sendCard.addView(label("Port")); port = EditText(this).apply { setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(prefs.getString("pcPort", "8765")); setTextColor(ink) }; sendCard.addView(port)
        val addressWatcher=object:android.text.TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!applyingEndpoint){selectedId="";prefs.edit().putString("lastDeviceId","").apply();showTarget(state="?섎룞 IP 쨌 ?곌껐 ?뺤씤 ?꾩슂")}};override fun afterTextChanged(s:android.text.Editable?) {}}
        ip.addTextChangedListener(addressWatcher);port.addTextChangedListener(addressWatcher)
        selection = label("?꾩쭅 ?좏깮???뚯씪???놁뒿?덈떎."); sendCard.addView(selection)
        queueRows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};sendCard.addView(queueRows)
        sendCard.addView(button("?꾨즺쨌?쒖쇅 ??ぉ ?뺣━").apply{setOnClickListener{if(!batchRunning){selections.filter{it.state in listOf("?꾨즺 쨌 寃利??쇱튂","?쒖쇅")}.forEach{if(it.snapshot.isNotEmpty())File(it.snapshot).delete()};selections.removeAll{it.state in listOf("?꾨즺 쨌 寃利??쇱튂","?쒖쇅")};saveQueue();renderQueue()}}})
        choose = button("?щ윭 ?뚯씪 異붽?"); check = button("?곌껐 ?뺤씤"); send = button("蹂대궡湲?/ ?댁뼱蹂대궡湲?, true); cancel = button("?꾩껜 ?꾩넚 痍⑥냼").apply { isEnabled = false }
        listOf(choose, check, send, cancel).forEach { sendCard.addView(it) }
        sendCard.addView(button("?대뜑 異붽?").apply{setOnClickListener{if(!batchRunning)startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},11)}})
        pairButton=button("??湲곌린 ?곌껐 ?뱀씤 ?붿껌");sendCard.addView(pairButton);pairButton.setOnClickListener{pairDevice()}
        sendBar = bar(); sendCard.addView(sendBar); sendState = label("諛쏅뒗 湲곌린?먯꽌 DropShip??癒쇱? ?ㅽ뻾??二쇱꽭??"); sendCard.addView(sendState);sendCard.addView(button("留덉?留??ㅻ쪟 蹂듭궗").apply{setOnClickListener{copyError()}})
        choose.setOnClickListener {
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_LOCAL_ONLY, true); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true) }, 10) }
            catch (_: Exception) { sendState.text = "?뚯씪 ?좏깮李쎌쓣 ?댁? 紐삵뻽?듬땲?? ?뚯씪愿由ъ옄瑜??뺤씤??二쇱꽭??" }
        }
        check.setOnClickListener { runSend(false) }; send.setOnClickListener { runSend(true) }; cancel.setOnClickListener { cancelBatch() }
        receiveCard.addView(label("?ш린濡?諛곕떖??二쇱꽭??, 21f, true));
        deviceName=prefs.getString("deviceName",Build.MODEL.take(40))?:"Android"
        myName=EditText(this).apply{setSingleLine();setText(deviceName);hint="??湲곌린 ?대쫫"};receiveCard.addView(myName)
        receiveCard.addView(button("湲곌린 ?대쫫 ???).apply{setOnClickListener{try{deviceName=DiscoveryWire.name(myName.text.toString());prefs.edit().putString("deviceName",deviceName).apply();Toast.makeText(this@MainActivity,"??ν뻽?듬땲?? ?곷? 湲곌린?먯꽌 ?ㅼ떆 寃?됲빐 二쇱꽭??",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this@MainActivity,e.message,Toast.LENGTH_SHORT).show()}}}); receiveState = label("?섏떊 以鍮?以묅?, 17f, true); receiveCard.addView(receiveState)
        address = label("??IP ?뺤씤 以묅?, 20f, true); receiveCard.addView(address)
        receiveCard.addView(label("Port  8765"))
        receiveFolder=label("諛쏆쓣 ?대뜑 쨌 "+if(prefs.getString("receiveTree","").isNullOrEmpty())"癒쇱? ?대뜑瑜??좏깮??二쇱꽭??" else "?좏깮???대뜑");receiveCard.addView(receiveFolder)
        receiveCard.addView(button("諛쏆쓣 ?대뜑 ?좏깮").apply{setOnClickListener{openReceiveFolder()}})
        receiveCard.addView(label("泥섏쓬 ??踰??섏떊 ?대뜑瑜??좏깮?댁빞 ?뚯씪??諛쏆쓣 ???덉뒿?덈떎. ?대? ??μ냼??Download/DropShip 媛숈? ?섏쐞 ?대뜑瑜??좏깮?섏꽭??",13f))
        receiveCard.addView(label("?뱀씤??湲곌린",18f,true));registeredRows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};receiveCard.addView(registeredRows)
        receiveCard.addView(button("誘몄셿猷??섏떊 ?뺣━").apply{setOnClickListener{try{receiveState.text="${service?.clearIncomplete()?:0}媛?誘몄셿猷??섏떊???뺣━?덉뒿?덈떎."}catch(e:Exception){receiveState.text=e.message}}})
        receiveCard.addView(button("IP ?덈줈 ?뺤씤").apply { setOnClickListener { updateAddress() } })
        receiveBar = bar(); receiveCard.addView(receiveBar); current = label("?곷? 湲곌린?먯꽌 ??二쇱냼濡??뚯씪??蹂대궡 二쇱꽭??"); receiveCard.addView(current);receiveCard.addView(button("留덉?留??ㅻ쪟 蹂듭궗").apply{setOnClickListener{copyError()}})
        val historyCard = card(); historyCard.addView(label("理쒓렐 ?꾩넚 湲곕줉 쨌 理쒕? 100媛?, 18f, true)); history = label("?꾩쭅 ?꾩넚???뚯씪???놁뒿?덈떎.", 13f); historyCard.addView(history); root.addView(historyCard);historyCard.removeViewAt(0);history.visibility=View.GONE;val historyToggle=button("理쒓렐 諛곗넚 湲곕줉  ??);historyCard.addView(historyToggle,0);historyToggle.setOnClickListener{val open=history.visibility!=View.VISIBLE;history.visibility=if(open)View.VISIBLE else View.GONE;historyToggle.text=if(open)"理쒓렐 諛곗넚 湲곕줉  ?? else "理쒓렐 諛곗넚 湲곕줉  ??}
        root.addView(label("媛숈? ?좊ː?????덈뒗 Wi-Fi?먯꽌 ?ъ슜?섏꽭??\n?꾩넚 以????깃낵 ?붾㈃??耳??먯꽭??\n???붾㈃?쇰줈 ?대룞?섎㈃ ?꾩넚쨌?섏떊??以묒??⑸땲??", 12f))
        try{val saved=JSONArray(prefs.getString("history","[]"));for(i in 0 until minOf(100,saved.length()))recent.addLast(saved.getString(i));history.text=recent.joinToString("\n\n")}catch(_:Exception){}
        arrangeSendCard();restoreQueue();renderQueue();refreshRegistered();showTarget();handleShared(intent)
    }
    override fun onStart() {
        super.onStart(); active = true; updateAddress(); main.post(refresh);startServices();main.postDelayed({if(!prefs.getBoolean("wizardComplete",false)&&!helperAutoShown){helperAutoShown=true;showHelper()}else promptReceiveFolder()},300)
    }
    private fun startServices() {
        val thisEpoch = ++epoch
        receiver?.close();receiver=null;discovery?.close();discovery=null
        try{multicastLock?.release()}catch(_:Exception){};multicastLock=null
        try {
            val store=SafStore(this,{prefs.getString("receiveTree","")?:""})
            val events = object : ReceiveEvents {
                override fun progress(name: String, bytes: Long, total: Long) { main.post { if (!active || epoch != thisEpoch) return@post; receiveState.text = "???뚯씪 ?섏떊 以?; receiveBar.progress = if (total > 0) (bytes.toDouble() * 1000 / total).toInt() else 0; current.text = "$name\n${mb(bytes)} / ${mb(total)} MB" + if (bytes == total) "\n??Β룰?利?以묅? else "" } }
                override fun completed(receipt: Receipt) { main.post { if (!active || epoch != thisEpoch) return@post; receiveState.text = "???섏떊 ?꾨즺 쨌 ?ㅼ쓬 ?뚯씪 ?섏떊 媛??; receiveBar.progress = 1000; current.text = "${receipt.name}\n${mb(receipt.size)} MB 쨌 SHA-256 ?쇱튂"; addHistory("諛쏆쓬", receipt.name, receipt.size) } }
                override fun failed(message: String) { main.post { if (!active || epoch != thisEpoch) return@post; receiveState.text = "?섏떊 ?ㅽ뙣 쨌 ?ㅻ쪟瑜??뺤씤?섏꽭??"; receiveBar.progress = 0; current.text = message;saveError("諛쏆쓬",message);addHistory("諛쏆쓬 쨌 ?ㅽ뙣",message,0) } }
            }
            val v2=V2Service(book,TransferRepository(File(filesDir,"incoming"),store),events);service=v2;receiver=ReceiverV2(v2);receiveState.text=if(prefs.getString("receiveTree","").isNullOrEmpty())"???곌껐 ?뱀씤 媛??쨌 癒쇱? 諛쏆쓣 ?대뜑瑜??좏깮?섏꽭??" else "???뱀씤??湲곌린 ?섏떊 以鍮??꾨즺"
            try{val wifi=applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager;multicastLock=wifi.createMulticastLock("DropShip-discovery-v05").apply{setReferenceCounted(false);acquire()};discovery=DiscoveryResponder(deviceId,{deviceName},"android")}catch(_:Exception){receiveState.text="???섏떊 以鍮??꾨즺 쨌 ?먮룞 寃??遺덇?, ?섎룞 IP瑜??ъ슜?섏꽭??"}
        } catch (_: Exception) { receiveState.text = "?섏떊???쒖옉?섏? 紐삵뻽?듬땲??\n?ㅻⅨ 踰꾩쟾???깆쓣 ?リ퀬 ?ㅼ떆 ?ㅽ뻾??二쇱꽭??" }
        scanDevices()
    }
    override fun onStop() {
        helper?.dismiss();helper=null;helperState=null
        setupDialog?.dismiss();setupDialog=null
        if(approvalDialog!=null){notified.remove(shownTicket);approvalDialog?.dismiss();approvalDialog=null}
        active = false; epoch++; main.removeCallbacks(refresh); receiver?.close(); receiver = null; discovery?.close();discovery=null;try{multicastLock?.release()}catch(_:Exception){};multicastLock=null;scanCancel?.set(true);if(batchRunning)cancelBatch()
        prefs.edit().putString("pcIp", ip.text.toString().trim()).putString("pcPort", port.text.toString().trim()).apply()
        saveQueue();super.onStop()
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleShared(intent)}
    private fun handleShared(intent:Intent?){if(intent==null||intent.action !in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))return;val uris=mutableListOf<Uri>();if(intent.action==Intent.ACTION_SEND_MULTIPLE){intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let{uris.addAll(it)}}else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let{uris.add(it)};if(uris.isEmpty())intent.clipData?.let{clip->for(i in 0 until clip.itemCount)uris.add(clip.getItemAt(i).uri)};if(uris.isEmpty()){Toast.makeText(this,"?ъ쭊쨌?뚯씪???좏깮??怨듭쑀留?吏?먰빀?덈떎.",Toast.LENGTH_SHORT).show()};addUris(uris.filter{it.scheme=="content"},intent.flags);intent.action=Intent.ACTION_MAIN;showPage(true)}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==71){main.postDelayed({showHelper();if(resultCode==RESULT_OK)try{applyQr(data?.getStringExtra("qr")?:"")}catch(e:Exception){helperState?.text=e.message}},350);return};if(resultCode!=RESULT_OK||data==null)return
        if(requestCode==12){val uri=data.data?:return;try{contentResolver.takePersistableUriPermission(uri,(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION));if(!prefs.edit().putString("receiveTree",uri.toString()).commit())throw IOException("?대뜑 ?ㅼ젙????ν븯吏 紐삵뻽?듬땲??");receiveFolder.text="諛쏆쓣 ?대뜑 쨌 ${uri.lastPathSegment}";if(active)startServices()}catch(e:Exception){receiveState.text="?대뜑 沅뚰븳 ????ㅽ뙣 쨌 ${e.message}"};return}
        if(requestCode==11){val tree=data.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);addTree(tree)}catch(e:Exception){sendState.text="?대뜑 異붽? ?ㅽ뙣 쨌 ${e.message}"};return}
        if(requestCode!=10)return;val uris=mutableListOf<Uri>();data.clipData?.let{clip->for(i in 0 until clip.itemCount)uris.add(clip.getItemAt(i).uri)};if(uris.isEmpty())data.data?.let{uris.add(it)};addUris(uris,data.flags)
    }
    private fun addUris(uris:List<Uri>,flags:Int){var rejected=0;uris.distinct().forEach{uri->try{if(selections.size>=1000)throw IOException("理쒕? 1000媛???ぉ");try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};var name="";var size=-1L;contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()){val n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);val z=c.getColumnIndex(OpenableColumns.SIZE);if(n>=0)name=c.getString(n)?:"";if(z>=0&&!c.isNull(z))size=c.getLong(z)}};V2Json.path(name);selections.add(Selection(uri,name,size))}catch(_:Exception){rejected++}};saveQueue();renderQueue();if(rejected>0)sendState.text="$rejected 媛???ぉ??異붽??섏? 紐삵뻽?듬땲??"}
    private fun addTree(tree:Uri){sendState.text="?대뜑 紐⑸줉???쎈뒗 以묅?;batchRunning=true;setBusy(true);Thread({try{val batch=V2Json.id();val staged=mutableListOf<Selection>();val rootUri=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree));fun walk(uri:Uri,relative:String,depth:Int){if(depth>31||staged.size+selections.size>=1000)throw IOException("?대뜑 理쒕? 1000媛???ぉ쨌32?④퀎 ?쒗븳");V2Json.path(relative);staged.add(Selection(uri,relative,0,batch=batch,directory=true));val children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,android.provider.DocumentsContract.getDocumentId(uri));contentResolver.query(children,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,android.provider.DocumentsContract.Document.COLUMN_SIZE),null,null,null)?.use{c->while(c.moveToNext()){val child=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));val path=relative+"/"+c.getString(1);V2Json.path(path);if(c.getString(2)==android.provider.DocumentsContract.Document.MIME_TYPE_DIR)walk(child,path,depth+1)else{if(staged.size+selections.size>=1000)throw IOException("理쒕? 1000媛???ぉ");staged.add(Selection(child,path,if(c.isNull(3))-1 else c.getLong(3),batch=batch))}}}?:throw IOException("?대뜑瑜??쎌쓣 ???놁뒿?덈떎.")};var name="";contentResolver.query(rootUri,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)};walk(rootUri,name,0);main.post{selections.addAll(staged);saveQueue();renderQueue();sendState.text="${staged.size}媛??대뜑쨌?뚯씪 ??ぉ 異붽?"}}catch(e:Exception){main.post{sendState.text=e.message}}finally{main.post{batchRunning=false;setBusy(false)}}},"DropShip-folder").start()}
    private fun refreshRegistered(){
        fun render(rows:LinearLayout){rows.removeAllViews();if(book.devices().isEmpty())rows.addView(label("?꾩쭅 ?깅줉??湲곌린媛 ?놁뼱?? 湲곌린 李얘린濡??곌껐??二쇱꽭??",12f));book.devices().forEach{d->
            rows.addView(button(directory.display(d.id,d.name)+(if(selectedId==d.id)" 쨌 ?좏깮?? else " 쨌 ?뚮윭 ?좏깮"),selectedId==d.id).apply{layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)};minHeight=dp(64);setPadding(dp(12),dp(10),dp(12),dp(10));isEnabled=!batchRunning;setOnClickListener{selectDevice(d.id,d.name)}})
            val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            actions.addView(button("蹂꾨챸 ?ㅼ젙").apply{isEnabled=!batchRunning;setOnClickListener{editAlias(d.id,d.name)}},LinearLayout.LayoutParams(0,-2,1f))
            actions.addView(button("?깅줉 ?댁젣").apply{isEnabled=!batchRunning;setOnClickListener{book.remove(d.id);directory.remove(d.id);if(selectedId==d.id){selectedId="";prefs.edit().putString("lastDeviceId","").apply();showTarget()};refreshRegistered()}},LinearLayout.LayoutParams(0,-2,1f));rows.addView(actions)
        }}
        render(registeredRows);render(sendRegisteredRows)
    }
    private fun showTarget(model:String?=null,state:String="?곌껐 ?뺤씤 ?꾩슂") {val name=book.find(selectedId)?.name?:model?:discovered[selectedId]?.name;targetLabel.text=if(name==null)"諛쏅뒗 湲곌린: ?섎룞 IP 쨌 ?곌껐 ?뺤씤 ?꾩슂" else "諛쏅뒗 湲곌린: ${directory.display(selectedId,name)}\n$state"}
    private fun applyEndpoint(endpoint:DeviceEndpoint){applyingEndpoint=true;try{ip.setText(endpoint.ip);port.setText(endpoint.port.toString())}finally{applyingEndpoint=false};prefs.edit().putString("pcIp",endpoint.ip).putString("pcPort",endpoint.port.toString()).apply()}
    private fun validateTarget(remote:RemoteInfo,expected:String){if(expected.isNotEmpty()&&remote.id!=expected)throw V2Error(403,"?좏깮??湲곌린? ?ㅻⅨ 湲곌린媛 ?묐떟?덉뒿?덈떎. ?ㅼ떆 寃?됲빐 二쇱꽭??")}
    private fun selectDevice(id:String,model:String){if(batchRunning||!active)return;scanCancel?.set(true);selectedId=id;prefs.edit().putString("lastDeviceId",id).apply();showPage(true);showTarget(model,"?곌껐 ?뺤씤 以묅?);batchRunning=true;setBusy(true);val control=SendControl();operation=control;val ownEpoch=epoch
        Thread({try{val found=DiscoveryScanner.scan(deviceId,control.cancelled);control.check();val endpoint=found.firstOrNull{it.id==id}?.endpoint?:directory.hint(id)?:throw V2Error(404,"?곷? 湲곌린瑜?李얠? 紐삵뻽?듬땲?? 媛숈? Wi-Fi?먯꽌 ?곷? ?깆쓣 耳쒓퀬 ?ㅼ떆 ?좏깮?섏꽭??");val remote=ClientV2(book).inspect(endpoint,control);validateTarget(remote,id);directory.seen(id,endpoint);main.post{if(!active||epoch!=ownEpoch)return@post;found.forEach{discovered[it.id]=it};applyEndpoint(endpoint);showTarget(model,if(remote.receiveReady)"?곌껐 媛?? else "?섏떊 ?대뜑 ?ㅼ젙 ?꾩슂");sendState.text=if(!remote.receiveReady)remote.receiveMessage else if(book.find(id)==null)"?곌껐 媛??쨌 ?곌껐 ?뱀씤???붿껌?섏꽭??" else "?곌껐 媛??쨌 ?뚯씪??異붽?????蹂대궡?몄슂."}}catch(e:Exception){main.post{if(active&&epoch==ownEpoch){showTarget(model,"?곌껐 ?뺤씤 ?ㅽ뙣");showSendError("湲곌린 ?좏깮",e)}}}finally{operation=null;main.post{batchRunning=false;setBusy(false);refreshRegistered()}}},"DropShip-select").start()
    }
    private fun editAlias(id:String,model:String){if(batchRunning)return;val input=EditText(this).apply{setSingleLine();setText(directory.get(id).alias);hint="?? ?쇱씠 Wing"};val dialog=android.app.AlertDialog.Builder(this).setTitle("$model 쨌 蹂꾨챸 ?ㅼ젙").setMessage("紐⑤뜽紐낆? 洹몃?濡??좎??⑸땲?? 鍮꾩슦硫?紐⑤뜽紐낅쭔 ?쒖떆?⑸땲??").setView(input).setPositiveButton("???,null).setNegativeButton("痍⑥냼",null).create();dialog.setOnShowListener{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{try{directory.setAlias(id,input.text.toString());refreshRegistered();showTarget();dialog.dismiss()}catch(e:Exception){input.error=e.message}}};dialog.show()}
    private fun openReceiveFolder(){if(batchRunning)return;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)},12)}
    private fun promptReceiveFolder(){if(!helperAutoShown&&!prefs.getBoolean("wizardComplete",false))return;if(!active||helper!=null||batchRunning||approvalDialog!=null||folderPromptShown||!prefs.getString("receiveTree","").isNullOrEmpty())return;folderPromptShown=true;setupDialog=android.app.AlertDialog.Builder(this).setTitle("癒쇱? 諛쏆쓣 ?대뜑瑜??좏깮?섏꽭??).setMessage("?뚯씪??諛쏆쑝?ㅻ㈃ 泥섏쓬 ??踰?????대뜑瑜??좏깮?댁빞 ?⑸땲?? ?대? ??μ냼??Download/DropShip 媛숈? ?섏쐞 ?대뜑瑜?留뚮뱾???좏깮?섏꽭??").setPositiveButton("?대뜑 ?좏깮"){_,_->openReceiveFolder()}.setNegativeButton("?섏쨷??,null).create().also{it.setOnDismissListener{setupDialog=null};it.show()}}
    private fun errorText(e:Exception)=(if(e is V2Error)"HTTP ${e.code} 쨌 " else "")+(e.message?:e.javaClass.simpleName)
    private fun saveError(action:String,message:String){lastError="$action 쨌 $message";prefs.edit().putString("lastError",lastError).apply()}
    private fun showSendError(action:String,e:Exception){val message=errorText(e);saveError(action,message);sendState.text=lastError;addHistory("?ㅻ쪟",lastError,0)}
    private fun copyError(){if(lastError.isEmpty()){Toast.makeText(this,"??λ맂 ?ㅻ쪟媛 ?놁뒿?덈떎.",Toast.LENGTH_SHORT).show();return};(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("DropShip ?ㅻ쪟",lastError));Toast.makeText(this,"?ㅻ쪟瑜?蹂듭궗?덉뒿?덈떎.",Toast.LENGTH_SHORT).show()}
    private fun checkPairRequests(){if(approvalDialog!=null||setupDialog!=null||!active)return;val ticket=book.pending().firstOrNull{it.requestId !in notified}?:return;notified.add(ticket.requestId);shownTicket=ticket.requestId;val dialog=android.app.AlertDialog.Builder(this).setTitle("湲곌린 ?곌껐 ?뱀씤").setMessage("${ticket.name}\n?뺤씤 肄붾뱶 ${ticket.code}\n?붿껌??湲곌린 ?붾㈃??肄붾뱶? 媛숈쓣 ?뚮쭔 ?뱀씤?섏꽭??").setPositiveButton("?뱀씤"){_,_->try{book.decide(ticket.requestId,true);refreshRegistered()}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_SHORT).show()}}.setNegativeButton("嫄곗젅"){_,_->try{book.decide(ticket.requestId,false)}catch(_:Exception){}}.setCancelable(false).create();approvalDialog=dialog;dialog.setOnDismissListener{approvalDialog=null;shownTicket=""};dialog.show()}
    private fun persistBeforeSend(control:SendControl){val saved=AtomicBoolean(false);val done=java.util.concurrent.CountDownLatch(1);main.post{try{saved.set(saveQueue())}finally{done.countDown()}};while(!done.await(100,java.util.concurrent.TimeUnit.MILLISECONDS))control.check();if(!saved.get())throw IOException("?꾩넚 以鍮??뺣낫瑜???ν븯吏 紐삵뻽?듬땲?? ?????怨듦컙???뺤씤?섏꽭??")}
    private fun pairDevice(){if(batchRunning)return;val ep=try{DeviceEndpoint.parse(ip.text.toString(),port.text.toString())}catch(e:Exception){sendState.text=e.message;return};val expected=selectedId;scanCancel?.set(true);batchRunning=true;setBusy(true);val control=SendControl();operation=control;Thread({try{val sender=ClientV2(book);validateTarget(sender.inspect(ep,control),expected);sender.pair(ep,{name,code->main.post{sendState.text="$name 쨌 ?뺤씤 肄붾뱶 $code\n?곷? 湲곌린?먯꽌 媛숈? 肄붾뱶瑜??뺤씤?섍퀬 ?뱀씤?섏꽭??";helperState?.text=sendState.text}},control);main.post{refreshRegistered();sendState.text="??湲곌린瑜??깅줉?덉뒿?덈떎.";helperState?.text="??湲곌린 ?깅줉 ?꾨즺! [?뚯뒪???뚯씪 蹂대궡湲?濡??ㅼ젣 ?꾩넚???뺤씤?섏꽭??"}}catch(e:Exception){main.post{sendState.text="?곌껐 ?뱀씤 ?ㅽ뙣 쨌 ${e.message}";helperState?.text=sendState.text}}finally{operation=null;main.post{batchRunning=false;setBusy(false)}}},"DropShip-pair").start()}
    private fun renderQueue() {
        queueRows.removeAllViews();selection.text=if(selections.isEmpty())"?꾩쭅 ?댁? ?뚯씪???놁뼱?? ?ъ쭊, ?뚯븙, 臾몄꽌瑜??댁븘 蹂댁꽭??" else "${selections.size}媛??댁쓬 쨌 ?湲?${selections.count{it.state=="?湲?}}媛?
        selections.forEach { item ->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(label("${item.name}\n${item.state}"),LinearLayout.LayoutParams(0,-2,1f))
            row.addView(button("痍⑥냼").apply{isEnabled=item.state=="?湲?||item.state.startsWith("以묐떒")||item.state.startsWith("?ㅽ뙣")||item===currentSelection;setOnClickListener{item.cancelled.set(true);if(item===currentSelection)operation?.cancel() else {item.state="?쒖쇅";saveQueue();addHistory("蹂대깂 쨌 痍⑥냼",item.name,0)};renderQueue()}},LinearLayout.LayoutParams(dp(75),-2))
            queueRows.addView(row)
        }
    }
    private fun cancelBatch(){stopBatch.set(true);operation?.cancel();selections.filter{it.state=="?湲?}.forEach{it.cancelled.set(true);it.state="以묐떒 쨌 ?댁뼱蹂대궡湲?;addHistory("蹂대깂 쨌 痍⑥냼",it.name,0)};saveQueue();renderQueue()}
    private fun setBusy(busy:Boolean){listOf(choose,check,send,ip,port,scanButton,pairButton).forEach{it.isEnabled=!busy};fun enable(v:View){v.isEnabled=!busy;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)enable(v.getChildAt(i))};enable(registeredRows);enable(sendRegisteredRows);for(i in 0 until peerRows.childCount)peerRows.getChildAt(i).isEnabled=!busy;cancel.isEnabled=busy}
    private fun runSend(transfer:Boolean) {
        if(batchRunning)return
        scanCancel?.set(true)
        val endpoint=try{DeviceEndpoint.parse(ip.text.toString(),port.text.toString()).also{if(transfer&&selections.none{it.state !in listOf("?꾨즺 쨌 寃利??쇱튂","?쒖쇅")})throw IOException("癒쇱? ?щ윭 ?뚯씪???좏깮??二쇱꽭??")}}catch(e:Exception){sendState.text=e.message;return}
        val expected=selectedId
        prefs.edit().putString("pcIp",endpoint.ip).putString("pcPort",endpoint.port.toString()).apply()
        val pending=selections.filter{it.state !in listOf("?꾨즺 쨌 寃利??쇱튂","?쒖쇅")}.toList();batchRunning=true;stopBatch.set(false);setBusy(true);sendBar.progress=0
        Thread({
            var ok=0;var failed=0;var cancelled=0;var lastFailure=""
            try {
                val sender=ClientV2(book)
                if(!transfer){val control=SendControl();operation=control;val remote=sender.inspect(endpoint,control);validateTarget(remote,expected);directory.seen(remote.id,endpoint);main.post{selectedId=remote.id;prefs.edit().putString("lastDeviceId",selectedId).apply();showTarget(remote.name,if(remote.receiveReady)"?곌껐 媛?? else "?섏떊 ?대뜑 ?ㅼ젙 ?꾩슂");sendState.text=if(!remote.receiveReady)remote.receiveMessage else if(book.find(remote.id)!=null)"???뱀씤??湲곌린 쨌 ?곌껐 媛?? else "???곌껐 媛??쨌 ?곌껐 ?뱀씤???꾩슂?⑸땲??"};return@Thread}
                val hello=SendControl();operation=hello;val remote=sender.inspect(endpoint,hello);validateTarget(remote,expected);if(!remote.receiveReady)throw V2Error(409,remote.receiveMessage);val target=remote.id;directory.seen(target,endpoint);main.post{selectedId=target;prefs.edit().putString("lastDeviceId",target).apply();showTarget(remote.name,"?꾩넚 ???)};if(book.find(target)==null)throw IOException("癒쇱? 湲곌린 ?곌껐???뱀씤??二쇱꽭??")
                for((index,item) in pending.withIndex()) {
                    if(stopBatch.get())break
                    if(item.state=="?쒖쇅")continue;item.cancelled.set(false)
                    val control=SendControl();operation=control;currentSelection=item;var temp:File?=null
                    // A cancel arriving between selection and operation assignment must still win.
                    if(item.cancelled.get()||stopBatch.get())control.cancel()
                    item.state="以鍮?以?;main.post{renderQueue();sendState.text="${index+1}/${pending.size} 쨌 ${item.name}"}
                    try {
                        control.check();if(item.target.isNotEmpty()&&item.target!=target)throw IOException("以묐떒???묒뾽? ?먮옒 湲곌린瑜??좏깮??二쇱꽭??");item.target=target
                        if(!item.directory){
                            temp=File(outbox,item.id+".data")
                            if(!temp.exists()){
                                val available=StatFs(filesDir.absolutePath).availableBytes-4L*1024*1024;if(item.size>=0&&item.size>available)throw IOException("?곗쓽 以鍮?怨듦컙??遺議깊빀?덈떎.")
                                val preparing=File(temp.path+".preparing")
                                try{contentResolver.openInputStream(item.uri)?.use{input->FileOutputStream(preparing).use{output->val b=ByteArray(131072);var copied=0L;while(true){control.check();val n=input.read(b);if(n<0)break;if(copied+n>available)throw IOException("以鍮?怨듦컙??遺議깊빀?덈떎.");output.write(b,0,n);copied+=n};output.fd.sync()}}?:throw IOException("?먮낯 ?뚯씪???ㅼ떆 ?좏깮??二쇱꽭??");java.nio.file.Files.move(preparing.toPath(),temp.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)}finally{preparing.delete()}
                            };item.snapshot=temp.absolutePath
                        };persistBeforeSend(control)
                        val events=object:SendEvents {override fun progress(stage:String,bytes:Long,total:Long){main.post{if(operation!==control)return@post;sendState.text="${index+1}/${pending.size} 쨌 ${item.name}\n$stage 쨌 ${mb(bytes)} / ${mb(total)} MB";sendBar.progress=if(total>0)(bytes.toDouble()*1000/total).toInt() else 0}}}
                        val receipt=sender.send(endpoint,target,temp?:File(filesDir,"unused"),item.name,item.id,item.batch,item.directory,events,control);item.state="?꾨즺 쨌 寃利??쇱튂";temp?.delete();item.snapshot="";ok++;main.post{addHistory("蹂대깂 쨌 ?꾨즺",receipt.name,receipt.size)}
                    }catch(e:Exception){if(control.cancelled.get()){item.state="以묐떒 쨌 ?댁뼱蹂대궡湲?;cancelled++}else{item.state="?ㅽ뙣 쨌 ?댁뼱蹂대궡湲?;failed++};val outcome=item.state;val error=errorText(e);if(!control.cancelled.get())lastFailure=error;main.post{addHistory("蹂대깂 쨌 $outcome",item.name+(if(outcome.startsWith("以묐떒"))"" else " 쨌 $error"),0);if(outcome.startsWith("以묐떒"))sendState.text="?꾩넚 痍⑥냼 쨌 媛숈? ?곷?瑜??좏깮?섍퀬 ?댁뼱蹂대궪 ???덉뒿?덈떎." else {saveError(item.name,error);sendState.text=lastError}}}
                    finally{operation=null;currentSelection=null;main.post{saveQueue();renderQueue()}}
                }
                val summary="?湲곗뿴 泥섎━ 醫낅즺 쨌 ?꾨즺 $ok / ?ㅽ뙣 $failed / 吏꾪뻾 ?뚯씪 痍⑥냼 $cancelled"+(if(lastFailure.isEmpty())"" else "\n留덉?留??ㅻ쪟: $lastFailure");main.post{sendState.text=summary}
            }catch(e:Exception){main.post{showSendError("?곌껐 ?뺤씤",e)}}
            finally{operation=null;currentSelection=null;main.post{batchRunning=false;saveQueue();setBusy(false);renderQueue()}}
        },"DropShip-queue").start()
    }
    private fun addHistory(direction:String,name:String,size:Long){val date=java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.KOREA).format(java.util.Date());recent.addFirst("$date 쨌 $direction 쨌 $name\n${mb(size)} MB");while(recent.size>100)recent.removeLast();history.text=recent.joinToString("\n\n");prefs.edit().putString("history",JSONArray(recent.toList()).toString()).apply()}
    private fun scanDevices(){
        if(scanCancel!=null||batchRunning||!active)return
        val cancellation=AtomicBoolean(false);scanCancel=cancellation;scanButton.isEnabled=false;val thisEpoch=epoch
        Thread({try{val found=DiscoveryScanner.scan(deviceId,cancellation);main.post{if(!active||thisEpoch!=epoch||batchRunning)return@post;discovered.clear();found.forEach{discovered[it.id]=it;directory.seen(it.id,it.endpoint)};peerRows.removeAllViews();found.filter{book.find(it.id)==null}.forEach{peer->peerRows.addView(button(directory.display(peer.id,peer.name)+" 쨌 ?뱀씤 ?꾩슂").apply{setOnClickListener{selectDevice(peer.id,peer.name)}})};found.firstOrNull{it.id==selectedId}?.let{applyEndpoint(it.endpoint)};refreshRegistered();showTarget();sendState.text=if(found.isEmpty())"寃?됰맂 湲곌린媛 ?놁뒿?덈떎. ?깅줉??湲곌린瑜??꾨Ⅴ硫?湲곗뼲??二쇱냼?먯꽌???뺤씤?⑸땲??" else "${found.size}媛?湲곌린 寃??쨌 ?깅줉??湲곌린瑜??뚮윭 ?좏깮?섏꽭??"}}catch(e:Exception){main.post{if(active&&thisEpoch==epoch)showSendError("湲곌린 李얘린",e)}}finally{main.post{if(scanCancel===cancellation){scanCancel=null;scanButton.isEnabled=!batchRunning;if(active&&epoch!=thisEpoch)scanDevices()}}}},"DropShip-scan").start()
    }
    private fun updateAddress() {
        try {
            val ips = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress || it.isLinkLocalAddress }.mapNotNull { it.hostAddress }.distinct()
            address.text = if (ips.isEmpty()) "??IP ?놁쓬\nWi-Fi ?곌껐???뺤씤??二쇱꽭??" else "??IP\n" + ips.joinToString("\n")
        } catch (_: Exception) { address.text = "IP瑜??뺤씤?섏? 紐삵뻽?듬땲?? Wi-Fi瑜??뺤씤??二쇱꽭??" }
    }
    private fun mb(bytes: Long) = String.format(Locale.KOREA, "%.1f", bytes / 1048576.0)
    private fun showHelper(){
        if(!active||isFinishing||helper!=null)return
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(12))}
        body.addView(label("?쒕줈?쇱? 泥?諛곗넚???쒖옉?댁슂!",20f,true))
        body.addView(label("01  ?묒そ???쒕엻?쎌쓣 ?ㅽ뻾?섍퀬 媛숈? Wi-Fi???곌껐??二쇱꽭?? PC?먮뒗 EXE, ?대??곗뿉??APK媛 ?꾩슂?댁슂. ?꾩넚 以묒뿉?????깃낵 ?붾㈃??耳??먯꽭??",14f))
        body.addView(button("???대???諛쏆쓣 ?대뜑 ?좏깮").apply{setOnClickListener{helper?.dismiss();openReceiveFolder()}})
        body.addView(label("02  PC??[泥섏쓬 ?곌껐 ?꾩슦誘??먯꽌 QR肄붾뱶瑜??꾩썙二쇱꽭?? ?ㅼ틪? 二쇱냼 ?낅젰???꾩?二쇰ŉ ?먮룞 ?뱀씤?섏? ?딆븘??",14f))
        body.addView(button("??PC QR ?ㅼ틪").apply{setOnClickListener{if(batchRunning){helperState?.text="吏꾪뻾 以묒씤 ?묒뾽???앸궃 ???ㅼ틪?섏꽭??"}else{helper?.dismiss();startActivityForResult(Intent(this@MainActivity,QrScanActivity::class.java),71)}}})
        body.addView(button("QR ???湲곌린 李얘린 / ?섎룞 IP ?ъ슜").apply{setOnClickListener{helper?.dismiss();showPage(true);scanDevices()}})
        body.addView(label("03  ?꾨옒 踰꾪듉?쇰줈 ?곌껐???붿껌????PC? ?대??곗쓽 ?뺤씤 肄붾뱶媛 媛숈?吏 鍮꾧탳?섍퀬 PC?먯꽌 ?뱀씤?섏꽭??",14f))
        body.addView(button("???좏깮??PC???곌껐 ?뱀씤 ?붿껌").apply{setOnClickListener{pairDevice()}})
        body.addView(label("04  ?뚯뒪??TXT ?뚯씪??PC 諛쏆쓣 ?대뜑????ν븯怨?SHA-256 寃利앷퉴吏 ?뺤씤?댁슂.",14f))
        body.addView(button("???뚯뒪???뚯씪 蹂대궡湲?).apply{setOnClickListener{helperTest()}})
        helperState=label(if(prefs.getBoolean("wizardComplete",false))"???댁쟾???곌껐 ?뚯뒪?몃? ?꾨즺?덉뼱?? ?몄젣???ㅼ떆 ?뚯뒪?명븷 ???덉뼱??" else "PC瑜??ㅼ틪?섍굅??湲곌린 李얘린?먯꽌 PC瑜??좏깮??二쇱꽭??",14f,true);body.addView(helperState)
        body.addView(label("?곌껐 ?ㅽ뙣 ?? ?묒そ ???ㅽ뻾 ??媛숈? Wi-Fi ??PC 二쇱냼 ??諛쏆쓣 ?대뜑 沅뚰븳 ??VPN쨌寃뚯뒪??Wi-Fi쨌怨듭쑀湲?寃⑸━ ??PC 諛⑺솕踰??덉슜 ?쒖꽌濡??뺤씤?섏꽭?? 寃???ㅽ뙣留뚯쑝濡??먯씤???뺤젙???섎뒗 ?놁뼱?? 諛⑺솕踰??꾩껜瑜??꾩? 留덉꽭??\n?꾩넚? ?뷀샇?붾릺吏 ?딆쑝誘濡??좊ː?섎뒗 ?ㅽ듃?뚰겕?먯꽌 ?ъ슜?섏꽭??",12f))
        helper=android.app.AlertDialog.Builder(this).setTitle("DropShip 0.7.1 쨌 泥??곌껐 ?꾩슦誘?).setView(ScrollView(this).apply{addView(body)}).setNegativeButton("?섏쨷??/ ?リ린",null).create().also{d->d.setOnDismissListener{helper=null;helperState=null};d.show()}
    }
    private fun applyQr(text:String){
        val q=ConnectionQr.parse(text)
        selectedId=q.id;applyEndpoint(q.endpoint);prefs.edit().putString("lastDeviceId",q.id).apply();showTarget("QR濡??좏깮??PC","?곌껐 ?뱀씤 ?꾩슂");helperState?.text="PC 二쇱냼 ${q.endpoint.ip}:${q.endpoint.port} ?낅젰 ?꾨즺. [?곌껐 ?뱀씤 ?붿껌]???꾨Ⅴ怨?PC??肄붾뱶瑜??뺤씤?섏꽭??"
    }
    private fun helperTest(){
        if(batchRunning){helperState?.text="吏꾪뻾 以묒씤 ?묒뾽???앸궃 ???ㅼ떆 ?뚮윭二쇱꽭??";return}
        val ep=try{DeviceEndpoint.parse(ip.text.toString(),port.text.toString())}catch(e:Exception){helperState?.text="癒쇱? PC瑜??좏깮?섍굅??QR???ㅼ틪?섏꽭??";return}
        val expected=selectedId
        if(expected.isEmpty()||book.find(expected)==null){helperState?.text="癒쇱? PC ?곌껐???뱀씤?섍퀬 ?깅줉?섏꽭??";return}
        scanCancel?.set(true);batchRunning=true;setBusy(true);val control=SendControl();operation=control;val ownEpoch=epoch
        helperState?.text="?뚯뒪??TXT ?꾩넚 諛?寃利?以묅?
        Thread({val file=File(cacheDir,"wizard-${V2Json.id()}.txt");try{val found=DiscoveryScanner.scan(deviceId,control.cancelled);val endpoint=found.firstOrNull{it.id==expected}?.endpoint?:ep;val sender=ClientV2(book);val remote=sender.inspect(endpoint,control);validateTarget(remote,expected);directory.seen(expected,endpoint);main.post{if(active&&epoch==ownEpoch)applyEndpoint(endpoint)};if(!remote.receiveReady)throw IOException(remote.receiveMessage);file.writeText("DropShip 0.7.1 ?곌껐 ?뚯뒪???깃났!\n");val name="DropShip-?곌껐?뚯뒪??${System.currentTimeMillis()}.txt";val receipt=sender.send(endpoint,expected,file,name,V2Json.id(),V2Json.id(),false,object:SendEvents{override fun progress(stage:String,bytes:Long,total:Long){}},control);main.post{if(active&&epoch==ownEpoch){prefs.edit().putBoolean("wizardComplete",true).apply();helperState?.text="??泥?諛곗넚 ?깃났! PC ???諛?SHA-256 寃利앹쓣 ?뺤씤?덉뼱??";sendState.text="?꾩슦誘??뚯뒪???꾩넚 ?깃났 쨌 SHA-256 ?쇱튂";addHistory("?꾩슦誘?쨌 ?뚯뒪???깃났",receipt.name,receipt.size)}}}catch(e:Exception){main.post{if(active&&epoch==ownEpoch)helperState?.text="?뚯뒪???ㅽ뙣 쨌 ${e.message}\nPC ?굿톆i-Fi쨌諛쏆쓣 ?대뜑쨌諛⑺솕踰??덉슜???뺤씤?섏꽭??"}}finally{file.delete();operation=null;main.post{batchRunning=false;setBusy(false)}}},"DropShip-helper-test").start()
    }
}
