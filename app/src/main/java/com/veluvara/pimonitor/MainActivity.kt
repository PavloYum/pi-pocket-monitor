package com.veluvara.pimonitor

import android.app.Activity
import android.os.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private var page=0
    private lateinit var content: LinearLayout
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable { override fun run() { if(page!=2) render(); handler.postDelayed(this,3000) } }
    private val dark get()=(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
    private val bg get()=Color.parseColor(if(dark) "#101917" else "#F2F5F1")
    private val surface get()=Color.parseColor(if(dark) "#1C2925" else "#FFFFFF")
    private val ink get()=Color.parseColor(if(dark) "#E6EEE8" else "#18362C")
    private val muted get()=Color.parseColor(if(dark) "#A9BDB1" else "#667A70")
    private val accent=Color.parseColor("#238260")
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(color:Int,r:Int=22)=GradientDrawable().apply { setColor(color); cornerRadius=dp(r).toFloat() }
    private fun text(value:String,size:Float=15f,color:Int=ink,bold:Boolean=false)=TextView(this).apply {
        text=value; textSize=size; setTextColor(color); setPadding(0,dp(5),0,dp(5)); if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun card(parent:LinearLayout=content,color:Int=surface)=column().apply {
        background=shape(color); setPadding(dp(20),dp(16),dp(20),dp(16))
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
    }
    private fun button(parent:LinearLayout,title:String,primary:Boolean=false,action:()->Unit) {
        parent.addView(Button(this).apply {
            text=title; isAllCaps=false; textSize=15f; setTextColor(if(primary) Color.WHITE else ink)
            background=shape(if(primary) accent else bg,14)
            setOnClickListener { action() }
        },LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin=dp(12) })
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=column().apply { setBackgroundColor(bg) }
        root.setOnApplyWindowInsetsListener { v,insets ->
            v.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom); insets
        }
        val header=column().apply { setPadding(dp(24),dp(14),dp(24),dp(12)) }
        header.addView(text("PI / POCKET MONITOR",12f,accent,true))
        header.addView(text("Сервер под рукой",27f,ink,true)); root.addView(header)
        content=column().apply { setPadding(dp(20),dp(8),dp(20),dp(16)) }
        root.addView(ScrollView(this).apply { isFillViewport=true; addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this).apply { setPadding(dp(12),dp(8),dp(12),dp(8)); setBackgroundColor(surface) }
        listOf("Обзор","История","Настройки").forEachIndexed { index,title ->
            nav.addView(Button(this).apply {
                text=title; isAllCaps=false; textSize=13f; setTextColor(ink); background=shape(surface,12)
                setOnClickListener { page=index; render(); for(i in 0 until nav.childCount) nav.getChildAt(i).background=shape(if(i==page) bg else surface,12) }
            },LinearLayout.LayoutParams(0,dp(48),1f))
        }
        root.addView(nav); setContentView(root); render()
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private fun endpoints()=prefs.getString("urls","")!!.lines().filter { it.isNotBlank() }
    private fun name(url:String):String {
        val host=runCatching { URI(url).host ?: url }.getOrDefault(url)
        return when { url.contains("/api/server/ping") -> "Immich"; url.contains("/healthz") -> "n8n"; else -> host }
    }
    private fun render() {
        content.removeAllViews()
        when(page) { 0->overview(); 1->history(); else->settings() }
    }
    private fun overview() {
        val entries=endpoints(); val active=MonitorService.active
        val fresh=entries.count { System.currentTimeMillis()-prefs.getLong("endpoint:$it:checked",0)<(prefs.getInt("minutes",3)+2)*60000L }
        val good=entries.count { prefs.getString("endpoint:$it:result","")=="ok" }
        val issues=entries.count { prefs.getBoolean("endpoint:$it:incident",false) }
        val deviceConfigured=prefs.getString("device_url", "")!!.isNotBlank()
        val deviceFresh=runCatching {
            val d=org.json.JSONObject(prefs.getString("device_json", "")!!)
            System.currentTimeMillis()/1000-d.getLong("collected_at")<180 && !d.optBoolean("stale",true) && prefs.getString("device_error", "")!!.isBlank()
        }.getOrDefault(false)
        val deviceIssue=prefs.getString("device_alert", "")!!.isNotBlank() || prefs.getInt("device_failures",0)>=3
        val title=when { entries.isEmpty() && !deviceConfigured->"Добавим первый сервис"; !active->"Мониторинг на паузе"; issues>0 || deviceIssue->"Нужно внимание"; fresh==entries.size && good==entries.size && (!deviceConfigured || deviceFresh)->"Сервисы отвечают"; else->"Ожидаем свежие данные" }
        val hero=card(color=Color.parseColor(if(dark) "#163D30" else "#DCECE1"))
        hero.addView(text(if(active) "●  МОНИТОРИНГ ВКЛЮЧЁН" else "○  МОНИТОРИНГ ВЫКЛЮЧЕН",11f,accent,true))
        hero.addView(text(title,25f,ink,true))
        hero.addView(text(if(entries.isEmpty() && !deviceConfigured) "Укажите адрес проверки в настройках." else "${entries.size} сервисов · каждые ${prefs.getInt("minutes",3)} мин\nОпрос при разблокированном экране",14f,muted))
        button(hero,if(active) "Приостановить" else "Включить мониторинг",true) {
            if(active) { stopService(Intent(this,MonitorService::class.java)); handler.postDelayed({ render() },200) }
            else if(entries.isEmpty() && prefs.getString("device_url", "")!!.isBlank()) { page=2; render() } else start()
        }
        deviceCard()
        content.addView(text("СЕРВИСЫ",12f,muted,true))
        entries.forEach { url ->
            val key="endpoint:$url"; val checked=prefs.getLong("$key:checked",0); val result=prefs.getString("$key:result","")
            val stale=System.currentTimeMillis()-checked>(prefs.getInt("minutes",3)+2)*60000L
            val status=when { checked==0L->"Ещё не проверен"; stale->"Данные устарели"; result=="ok"->"Доступен"; result=="network"->"Проверьте сеть телефона"; else->"Ошибка проверки" }
            card().apply {
                addView(text(name(url),20f,ink,true))
                addView(text(status,14f,if(result=="ok"&&!stale) accent else muted,true))
                addView(text(url,12f,muted))
                if(checked>0) addView(text("Последняя проверка · "+SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(checked)),12f,muted))
            }
        }
        content.addView(text("Проверяется доступность адресов. Показатели устройства доступны при подключённом источнике Pi.",12f,muted))
    }
    private fun start() {
        if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"),1)
        startForegroundService(Intent(this,MonitorService::class.java)); handler.postDelayed({ if(page==0) render() },250)
    }
    private fun deviceCard() {
        content.addView(text("УСТРОЙСТВО",12f,muted,true))
        val c=card()
        c.addView(text("Raspberry Pi",22f,ink,true))
        if(prefs.getString("device_url", "")!!.isBlank()) {
            c.addView(text("Подключите адрес мониторинга в настройках. Нужен Tailscale.",14f,muted)); return
        }
        val error=prefs.getString("device_error", "")!!
        if(error.isNotEmpty()) c.addView(text(error,14f,muted))
        val raw=prefs.getString("device_json", "")!!
        if(raw.isBlank()) { c.addView(text("Ожидаем первую сводку",14f,muted)); return }
        try {
            val d=org.json.JSONObject(raw)
            val age=System.currentTimeMillis()/1000-d.getLong("collected_at")
            c.addView(text(if(age>180 || d.optBoolean("stale",true)) "Данные устарели" else "Снимок ${age.coerceAtLeast(0)} сек назад",12f,muted))
            fun gb(n:Double)=String.format(Locale.getDefault(),"%.1f",n/1073741824)
            c.addView(text("CPU  ${d.getDouble("cpu_percent")}%    ·    ${if(d.isNull("temperature")) "—" else d.getDouble("temperature").toString()+" °C"}",20f,ink,true))
            c.addView(text("Память: ${gb(d.getDouble("memory_total")-d.getDouble("memory_available"))} / ${gb(d.getDouble("memory_total"))} ГБ",15f))
            c.addView(text("Swap: ${gb(d.getDouble("swap_total")-d.getDouble("swap_free"))} / ${gb(d.getDouble("swap_total"))} ГБ",14f,muted))
            c.addView(text("Без перезагрузки: ${d.getLong("uptime_seconds")/3600} ч",14f,muted))
            val disks=d.getJSONArray("disks")
            for(i in 0 until disks.length()) { val disk=disks.getJSONObject(i); c.addView(text("${disk.getString("name")}: свободно ${gb(disk.getDouble("free"))} / ${gb(disk.getDouble("total"))} ГБ",14f)) }
            val alerts=d.getJSONArray("alerts")
            for(i in 0 until alerts.length()) c.addView(text("! ${alerts.getString(i)}",14f,Color.parseColor("#B65B32"),true))
            val watched=d.optJSONArray("watched")
            if(watched!=null) {
                c.addView(text("Контролируемые сервисы",16f,ink,true))
                for(i in 0 until watched.length()) { val w=watched.getJSONObject(i); c.addView(text("${if(w.getBoolean("incident")) "!" else if(w.getInt("failures")>0) "○" else "●"} ${w.getString("name")} · попыток восстановления ${w.getInt("attempts")}",12f,muted)) }
            }
            val containers=d.getJSONArray("containers")
            c.addView(text("Контейнеры · ${containers.length()}",16f,ink,true))
            for(i in 0 until containers.length()) { val item=containers.getJSONObject(i); c.addView(text("${item.getString("name")} · ${item.getString("status")}",12f,muted)) }
        } catch(e:Exception) { c.addView(text("Не удалось прочитать сводку устройства",14f,muted)) }
    }
    private fun history() {
        content.addView(text("История событий",24f,ink,true))
        content.addView(text("Ошибки и восстановления. Успешные регулярные проверки скрыты.",14f,muted))
        val lines=prefs.getString("history","")!!.lines().filter { it.isNotBlank() && (!it.contains("HTTP 2") || it.contains("Восстанов")) }
        if(lines.isEmpty()) card().addView(text("Пока без событий\nЗдесь появятся проблемы и восстановление связи.",16f,muted))
        else lines.forEach { card().addView(text(it,14f)) }
    }
    private fun settings() {
        content.addView(text("Настройки",24f,ink,true))
        val c=card(); c.addView(text("Адреса сервисов",18f,ink,true))
        c.addView(text("По одному URL на строку. Каждый проверяется отдельно, включая домашние адреса вне дома.",13f,muted))
        c.addView(text("Мониторинг устройства · URL /status",14f,muted))
        val device=EditText(this).apply { setTextColor(ink); textSize=14f; hint="http://VPN-IP:8786/status"; setText(prefs.getString("device_url", "")); inputType=17 }; c.addView(device)
        val urls=EditText(this).apply { setTextColor(ink); setHintTextColor(muted); hint="https://example.com/health"; minLines=3; gravity=Gravity.TOP; textSize=14f; setText(prefs.getString("urls","")) }; c.addView(urls)
        c.addView(text("Интервал · 2–30 минут",14f,muted))
        val minutes=EditText(this).apply { setTextColor(ink); inputType=2; setText(prefs.getInt("minutes",3).toString()) }; c.addView(minutes)
        button(c,"Сохранить",true) {
            val entries=urls.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(); val n=minutes.text.toString().toIntOrNull()
            val deviceUrl=device.text.toString().trim()
            val deviceValid=deviceUrl.isBlank() || runCatching { val u=URI(deviceUrl); u.scheme in listOf("http","https") && !u.host.isNullOrBlank() && u.userInfo==null && u.fragment==null }.getOrDefault(false)
            val valid=deviceValid && (entries.isNotEmpty() || deviceUrl.isNotBlank()) && entries.size <=10 && entries.all { runCatching { val u=URI(it); u.scheme in listOf("http","https") && !u.host.isNullOrBlank() && u.userInfo==null && u.fragment==null }.getOrDefault(false) }
            if(!valid||n==null||n !in 2..30) Toast.makeText(this,"Нужны 1–10 URL и интервал 2–30 минут",Toast.LENGTH_LONG).show()
            else { prefs.edit().putString("device_url",deviceUrl).putString("urls",entries.joinToString("\n")).putInt("minutes",n).apply(); page=0; render() }
        }
        card().apply {
            addView(text("Работа в фоне",18f,ink,true))
            addView(text("Разрешите уведомления и фоновую работу. После перезагрузки телефона включите мониторинг снова. Для VPN-адреса нужен Tailscale. HTTP используйте только в доверенной сети.",14f,muted))
            button(this,"Настройки Android") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }
        }
        content.addView(text("Pi Pocket Monitor · 0.3.0\nДанные хранятся только на телефоне.",12f,muted))
    }
}
