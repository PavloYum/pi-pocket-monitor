package com.veluvara.pimonitor

import android.content.Context
import android.graphics.*
import android.view.View
import org.json.JSONArray

class MetricChart(context:Context,private val rows:JSONArray,private val metric:String,private val color:Int,private val labelColor:Int):View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val density=resources.displayMetrics.density
        val points=(0 until rows.length()).map { rows.getJSONObject(it) }.filter { !it.isNull(metric) && it.has(metric) }
        p.color=labelColor;p.textSize=12*density
        if(points.size<2) { canvas.drawText("Нужно минимум два замера",8*density,40*density,p);return }
        val max=points.maxOf { it.getDouble(metric) }.coerceAtLeast(1.0)
        val start=points.first().getLong("ts");val span=(points.last().getLong("ts")-start).coerceAtLeast(1)
        val left=8*density;val top=30*density;val bottom=height-30*density;val right=width-8*density
        canvas.drawText("макс. %.1f".format(max),left,18*density,p)
        p.strokeWidth=density;p.alpha=70
        for(i in 0..3) { val y=top+(bottom-top)*i/3;canvas.drawLine(left,y,right,y,p) };p.alpha=255
        val path=Path(); var previous:Long?=null
        val typical=span/(points.size-1)
        points.forEach {
            val ts=it.getLong("ts");val x=left+(right-left)*(ts-start).toFloat()/span;val y=bottom-(bottom-top)*(it.getDouble(metric)/max).toFloat()
            if(previous==null || ts-previous!!>maxOf(300,typical*3))path.moveTo(x,y) else path.lineTo(x,y)
            previous=ts
        }
        p.color=color;p.strokeWidth=2.5f*density;p.style=Paint.Style.STROKE;canvas.drawPath(path,p);p.style=Paint.Style.FILL;p.color=labelColor
        val format=java.text.SimpleDateFormat("dd.MM HH:mm",java.util.Locale.getDefault())
        canvas.drawText(format.format(java.util.Date(start*1000)),left,height-8*density,p)
        val end=format.format(java.util.Date(points.last().getLong("ts")*1000));canvas.drawText(end,right-p.measureText(end),height-8*density,p)
    }
}
