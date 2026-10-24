package com.example.avitoradar

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import java.util.*

data class AdItem(val title: String, val price: String, val url: String, val id: String)

class RadarService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val notifiedIds = mutableSetOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("URL") ?: ""
        val interval = intent?.getLongExtra("INTERVAL", 60L) ?: 60L

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "RADAR_CHANNEL")
            .setContentTitle("Avito Radar работает")
            .setContentText("Мониторинг объявлений активен")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        startForeground(1, notification)

        startMonitoring(url, interval)
        return START_STICKY
    }

    private fun startMonitoring(searchUrl: String, intervalSeconds: Long) {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (searchUrl.isNotEmpty()) {
                        val doc = Jsoup.connect(searchUrl)
                            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                            .timeout(10000)
                            .get()

                        val items = doc.select("div[data-marker='item']")
                        for (item in items) {
                            val titleElem = item.select("h3[itemprop='name']")
                            val title = titleElem.text()
                            val linkElem = item.select("a[itemprop='url']")
                            val relUrl = linkElem.attr("href")
                            val fullUrl = if (relUrl.startsWith("http")) relUrl else "https://www.avito.ru$relUrl"
                            val price = item.select("meta[itemprop='price']").attr("content")
                            val id = item.attr("data-item-id").ifEmpty { fullUrl }

                            if (id.isNotEmpty() && !notifiedIds.contains(id)) {
                                notifiedIds.add(id)
                                sendNewAdNotification(applicationContext, AdItem(title, "$price руб.", fullUrl, id))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(intervalSeconds * 1000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("RADAR_ALERTS", "Radar Alerts", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            val serviceChannel = NotificationChannel("RADAR_CHANNEL", "Service Channel", NotificationManager.IMPORTANCE_LOW)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun sendNewAdNotification(context: Context, ad: AdItem) {
        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ad.url))
        val pending = PendingIntent.getActivity(context, ad.id.hashCode(), openIntent, PendingIntent.FLAG_IMMUTABLE)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, "RADAR_ALERTS")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Найдено: ${ad.price}")
            .setContentText(ad.title)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(ad.id.hashCode(), notification)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                MainScreen(context = this)
            }
        }
    }
}

@Composable
fun MainScreen(context: Context) {
    var urlText by remember { mutableStateOf("") }
    var intText by remember { mutableStateOf("60") }
    var savedMsg by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Avito Radar", fontSize = 24.sp, color = Color(0xFF00E676))
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Ссылка на поиск Авито") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = intText,
            onValueChange = { intText = it },
            label = { Text("Интервал проверки (сек)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                val interval = intText.toLongOrNull() ?: 60L
                val intent = Intent(context, RadarService::class.java).apply {
                    putExtra("URL", urlText)
                    putExtra("INTERVAL", interval)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                savedMsg = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Запустить радар", color = Color.Black)
        }

        if (savedMsg) {
            Text("Радар запущен в фоне!", color = Color(0xFF00E676), fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Разрешить работу в фоне без ограничений батареи", color = Color(0xFF00E676))
        }
    }
}
