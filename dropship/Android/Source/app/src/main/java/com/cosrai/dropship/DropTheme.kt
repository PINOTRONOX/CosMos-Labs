package com.cosrai.dropship
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
object DropTheme {
 val purple=Color.rgb(109,79,196)
 fun shape(c:Context,color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=16*c.resources.displayMetrics.density}
 fun style(b:Button,primary:Boolean=false,danger:Boolean=false){
  val normal=if(primary)purple else if(danger)Color.rgb(255,239,241) else Color.rgb(239,232,255)
  val d=StateListDrawable().apply{addState(intArrayOf(-android.R.attr.state_enabled),shape(b.context,Color.rgb(239,237,244)));addState(intArrayOf(),shape(b.context,normal))}
  b.background=RippleDrawable(ColorStateList.valueOf(0x226D4FC4),d,shape(b.context,Color.WHITE));b.stateListAnimator=null
  b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.rgb(149,143,161),if(primary)Color.WHITE else if(danger)Color.rgb(158,54,79) else purple)))
 }
}
