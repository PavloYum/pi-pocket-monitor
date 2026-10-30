package com.veluvara.pimonitor

import android.app.*
import android.content.*
import android.os.*
import android.net.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorService : Service() {
    companion object { @Volatile var active=false; private set }
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var busy=false
    @Volatile private var stopped=false
    private val prefs by lazy { getSharedPreferences("monitor",MODE_PRIVATE) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val tick=object: Runnable {
        override fun run() {
            if (!stopped && unlocked() && !busy) {
                busy=true
                worker.execute { try { checkAll() } finally { handler.post { busy=false } } }
            }
            if (!stopped && unlocked()) handler.postDelayed(this,prefs.getInt("minutes",3)*60000L)
        }
    }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(c: Context?,i: Intent?) {
            handler.removeCallbacks(tick)
            if (i?.action==Intent.ACTION_USER_PRESENT) handler.post(tick)
        }
    }
    private fun unlocked()=getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel("service","Работа мониторинга",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("alerts","Проблемы сервера",NotificationManager.IMPORTANCE_DEFAULT))
        val filter=IntentFilter().apply { addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF) }
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver,filter)
    }
    private fun notification(channel:String,text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Pi Pocket Monitor").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).build()
    }
    override fun onStartCommand(intent:Intent?, flags:Int,startId:Int):Int {
        val stop=PendingIntent.getService(this,1,Intent(this,MonitorService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        if(intent?.action=="STOP") { stopSelf(); return START_NOT_STICKY }
        val n=Notification.Builder(this,"service").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Мониторинг включён").setContentText("Опрос только при разблокированном экране").setOngoing(true).addAction(Notification.Action.Builder(null,"Остановить",stop).build()).build()
        startForeground(1,n)
        active=true
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }
    private fun log(text:String) {
        val date=SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(Date())
        val lines=("$date $text\n"+prefs.getString("history","")).lines().take(100).joinToString("\n")
        prefs.edit().putString("history",lines).apply()
    }
    private fun alert(id:Int,text:String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching { manager.notify(id,notification("alerts",text)) }
    }
    private fun checkAll() {
        val cm=getSystemService(ConnectivityManager::class.java)
        val network=cm.activeNetwork
        if(network==null) {
            if(!prefs.getBoolean("offline",false)) { log("У телефона нет активной сети"); alert(2,"Нет сети телефона; состояние сервера неизвестно") }
            prefs.edit().putBoolean("offline",true).apply(); return
        }
        prefs.edit().putBoolean("offline",false).apply()
        val internet=cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        checkDevice()
        for((index,endpoint) in prefs.getString("urls","")!!.lines().filter { it.isNotBlank() }.withIndex()) {
            if(stopped || !unlocked()) return
            var connection:HttpURLConnection?=null
            val result=try {
                connection=URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout=5000; connection.readTimeout=5000; connection.instanceFollowRedirects=false
                val code=connection.responseCode
                Pair(code in 200..299,"HTTP $code")
            } catch(e:Exception) { Pair(false,"Нет ответа") } finally { connection?.disconnect() }
            if(stopped || !unlocked()) return
            val key="endpoint:$endpoint"; val incident=prefs.getBoolean("$key:incident",false)
            prefs.edit().putLong("$key:checked",System.currentTimeMillis()).putString("$key:result",if(result.first) "ok" else if(!internet) "network" else "error").apply()
            if(result.first) {
                if(incident) log("Восстановление: проверка ${index+1}")
                if(incident) alert(100+index,"Проверка ${index+1}: доступ восстановлен")
                prefs.edit().putInt("$key:failures",0).putBoolean("$key:incident",false).apply()
                log("Проверка ${index+1}: ${result.second}")
            } else if(!internet) {
                prefs.edit().putInt("$key:failures",0).apply()
                log("Проверка ${index+1}: нет ответа; интернет телефона не подтверждён")
            } else {
                val failures=prefs.getInt("$key:failures",0)+1
                prefs.edit().putInt("$key:failures",failures).apply()
                log("Проверка ${index+1}: ${result.second}, ошибок подряд $failures")
                if(failures>=3 && !incident) {
                    alert(100+index,"Проверка ${index+1}: три ошибки. Проверьте сеть, VPN и сервер.")
                    prefs.edit().putBoolean("$key:incident",true).apply()
                }
            }
        }
    }
    private fun checkDevice() {
        val url=prefs.getString("device_url", "")!!.trim()
        if(url.isEmpty() || stopped || !unlocked()) return
        var conn:HttpURLConnection?=null
        try {
            conn=URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout=5000; conn.readTimeout=5000; conn.instanceFollowRedirects=false
            if(conn.responseCode!=200) throw IllegalStateException("HTTP")
            val bytes=conn.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(4096)
                while(true) { val n=input.read(buffer); if(n<0) break; if(out.size()+n>262144) throw IllegalStateException("Size"); out.write(buffer,0,n) }; out.toByteArray()
            }
            val raw=String(bytes,Charsets.UTF_8)
            if(raw.length>262144) throw IllegalStateException("Size")
            val data=org.json.JSONObject(raw)
            if(data.getInt("schema")!=1) throw IllegalStateException("Schema")
            if(stopped || !unlocked()) return
            val alerts=data.getJSONArray("alerts")
            val stale=data.optBoolean("stale",true)
            val signature=(if(stale) "Данные устройства устарели. " else "")+(0 until alerts.length()).joinToString("; ") { alerts.getString(it) }
            val previous=prefs.getString("device_alert", "")!!
            if(signature.isNotEmpty() && signature!=previous) { alert(50,"Pi: $signature"); log("Pi: $signature") }
            if(signature.isEmpty() && previous.isNotEmpty()) { alert(50,"Pi: показатели вернулись в норму"); log("Восстановление: Pi") }
            if(prefs.getInt("device_failures",0)>=3) { alert(51,"Связь с мониторингом Pi восстановлена"); log("Восстановление: связь с Pi") }
            prefs.edit().putString("device_json",raw).putLong("device_checked",System.currentTimeMillis()).putString("device_error", "").putInt("device_failures",0).putString("device_alert",signature).apply()
        } catch(e:Exception) {
            val count=prefs.getInt("device_failures",0)+1
            prefs.edit().putString("device_error","Нет ответа. Проверьте Tailscale и связь с Pi.").putInt("device_failures",count).apply()
            if(count==3) { alert(51,"Нет связи с мониторингом Pi. Проверьте VPN и сеть."); log("Нет связи с мониторингом Pi") }
        } finally { conn?.disconnect() }
    }
    override fun onDestroy() { active=false; stopped=true; handler.removeCallbacks(tick); unregisterReceiver(receiver); worker.shutdownNow(); super.onDestroy() }
    override fun onBind(intent:Intent?) : IBinder?=null
}
