package com.cosrai.dropship
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
@Suppress("DEPRECATION")
class QrScanActivity:Activity(),SurfaceHolder.Callback,Camera.PreviewCallback {
    private var camera:Camera?=null
    private lateinit var surface:SurfaceView
    private lateinit var state:TextView
    private var ready=false
    private var reading=false
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,40,20,25)};state=TextView(this).apply{text="PC 도우미의 QR코드를 비춰주세요.\n카메라 영상은 기기 안에서만 처리합니다.";textSize=18f};root.addView(state);surface=SurfaceView(this);root.addView(surface,LinearLayout.LayoutParams(-1,0,1f));root.addView(Button(this).apply{text="취소 · 다른 방법으로 연결";setOnClickListener{finish()}});setContentView(root);surface.holder.addCallback(this);if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(android.Manifest.permission.CAMERA),21)}
    override fun onResume(){super.onResume();open()}
    override fun onPause(){release();super.onPause()}
    override fun surfaceCreated(holder:SurfaceHolder){ready=true;open()}
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int){}
    override fun surfaceDestroyed(holder:SurfaceHolder){ready=false;release()}
    private fun open(){if(!ready||camera!=null||checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;try{camera=Camera.open().also{c->val p=c.parameters;p.supportedFocusModes?.let{if(it.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))p.focusMode=Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE};val size=p.supportedPreviewSizes.filter{it.width<=1280&&it.height<=960}.maxByOrNull{it.width*it.height};if(size!=null)p.setPreviewSize(size.width,size.height);c.parameters=p;c.setDisplayOrientation(90);c.setPreviewDisplay(surface.holder);c.setPreviewCallback(this);c.startPreview()}}catch(_:Exception){release();state.text="카메라를 열지 못했어요. 뒤로 가서 기기 찾기나 수동 IP로 연결하세요."}}
    private fun release(){camera?.setPreviewCallback(null);try{camera?.stopPreview()}catch(_:Exception){};camera?.release();camera=null}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==21){if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)open() else state.text="카메라 권한이 없어 QR 스캔을 사용할 수 없어요. 취소 후 기기 찾기나 수동 IP를 이용하세요."}}
    override fun onPreviewFrame(data:ByteArray,c:Camera){if(reading)return;reading=true;try{val size=c.parameters.previewSize;val source=PlanarYUVLuminanceSource(data,size.width,size.height,0,0,size.width,size.height,false);val result=MultiFormatReader().apply{setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))}.decode(BinaryBitmap(HybridBinarizer(source)));setResult(RESULT_OK,Intent().putExtra("qr",result.text));finish()}catch(_:Exception){reading=false}}
}
