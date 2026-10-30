package com.veluvara.pimonitor

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.widget.*
import java.net.URI

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("monitor", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,48,32,24) }
        val scroll = ScrollView(this).apply { addView(box) }; setContentView(scroll)
        fun label(s: String) { box.addView(TextView(this).apply { text=s; textSize=18f; setPadding(0,16,0,16) }) }
        label("Pi Pocket Monitor · 0.1.0")
        label("Проверки работают после включения мониторинга, пока телефон разблокирован. При выключенном экране опрос прекращается.")
        label("Адреса проверки, по одному на строку. Каждый проверяется независимо. Для Immich используйте /api/server/ping. HTTP — только в доверенной сети; для Tailscale нужен включённый VPN.")
        val urls = EditText(this).apply { hint="https://your-server.example/api/server/ping"; minLines=3; setText(prefs.getString("urls", "")) }; box.addView(urls)
        label("Интервал, минуты (2–30)")
        val interval = EditText(this).apply { inputType=2; setText(prefs.getInt("minutes",3).toString()) }; box.addView(interval)
        fun button(s: String, f: () -> Unit) { box.addView(Button(this).apply { text=s; setOnClickListener { f() } }) }
        button("Сохранить и включить") {
            val entries=urls.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val valid=entries.size in 1..10 && entries.all { runCatching { val u=URI(it); u.scheme in listOf("http","https") && !u.host.isNullOrBlank() && u.userInfo==null && u.fragment==null }.getOrDefault(false) }
            val n=interval.text.toString().toIntOrNull()
            if (!valid || n==null || n !in 2..30) Toast.makeText(this,"Проверьте адреса и интервал",Toast.LENGTH_LONG).show()
            else {
                prefs.edit().putString("urls",entries.joinToString("\n")).putInt("minutes",n).apply()
                if(Build.VERSION.SDK_INT>=33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"),1)
                startForegroundService(Intent(this,MonitorService::class.java))
                Toast.makeText(this,"Мониторинг включён",Toast.LENGTH_SHORT).show()
            }
        }
        button("Остановить") { stopService(Intent(this,MonitorService::class.java)) }
        val history=TextView(this).apply { textSize=14f }; box.addView(history)
        button("Обновить журнал") { history.text=prefs.getString("history","Проверок пока нет") }
        history.text=prefs.getString("history","Проверок пока нет")
        label("Разрешите уведомления и фоновую работу в настройках Android. После перезагрузки телефона или принудительной остановки откройте приложение и включите мониторинг снова. Никаких команд управления сервером приложение не отправляет.")
    }
}
