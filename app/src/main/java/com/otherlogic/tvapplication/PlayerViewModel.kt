package com.otherlogic.tvapplication

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    interface Ui {
        fun showSetup()
        fun showPlayer(deviceId: String)
        fun showMessage(message: String)
        fun showImage(bitmap: Bitmap)
    }

    private var ui: Ui? = null
    private val prefs = PlayerPrefs(application.applicationContext)

    private var mqttClient: MqttAndroidClient? = null

    private val handler = Handler(Looper.getMainLooper())

    private var images: List<String> = emptyList()
    private var index = 0
    private var delayMs = 5000L

    private var carouselRunnable: Runnable? = null
    private var fallbackRunnable: Runnable? = null

    fun attach(ui: Ui) {
        this.ui = ui
    }

    fun start() {
        if (!prefs.isConfigured()) {
            ui?.showSetup()
        } else {
            connect(prefs.getBrokerUrl(), prefs.getDeviceId())
        }
    }

    fun saveSettingsAndConnect(brokerUrl: String, deviceId: String) {
        val cleanBrokerUrl = brokerUrl.trim()
        val cleanDeviceId = deviceId.trim()

        if (cleanBrokerUrl.isBlank() || cleanDeviceId.isBlank()) {
            ui?.showMessage("Enter broker URL and device ID")
            return
        }
        connect(cleanBrokerUrl, cleanDeviceId)
    }

    private fun connect(brokerUrl: String, deviceId: String) {
        mqttClient?.let {
            try {
                it.disconnect()
                it.unregisterResources()
            } catch (e: Exception) {}
        }

        mqttClient = MqttAndroidClient(
            getApplication<Application>().applicationContext,
            brokerUrl,
            "player_$deviceId",
            MemoryPersistence()
        )

        mqttClient?.setCallback(object : MqttCallbackExtended {

            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                try {
                    mqttClient?.subscribe("signage/all", 1)
                    mqttClient?.subscribe("signage/device/$deviceId", 1)

                    handler.post {
                        ui?.showMessage("Connected")
                        prefs.saveSettings(brokerUrl, deviceId)
                        ui?.showPlayer(deviceId)
                    }
                } catch (e: Exception) {
                    handler.post {
                        ui?.showMessage("Subscribe error: ${e.message}")
                    }
                }
            }

            override fun connectionLost(cause: Throwable?) {
                handler.post {
                    ui?.showMessage("Disconnected: ${cause?.message ?: "unknown"}")
                }
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payload = message?.toString() ?: return
                handleCommand(payload)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 20
            connectionTimeout = 10
        }

        try {
            // Add a timeout handler for connection
            val connectionTimeoutRunnable = Runnable {
                if (mqttClient?.isConnected != true) {
                    handler.post {
                        ui?.showMessage("Connection timeout: Check broker URL and network connectivity")
                    }
                }
            }
            handler.postDelayed(connectionTimeoutRunnable, 15000)

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    handler.removeCallbacks(connectionTimeoutRunnable)
                    // Success is already handled in connectComplete
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    handler.removeCallbacks(connectionTimeoutRunnable)

                    handler.post {
                        val mqttException =
                            exception as? org.eclipse.paho.client.mqttv3.MqttException

                        val reasonCode = mqttException?.reasonCode
                        val causeMessage = exception?.cause?.message
                        val exceptionMessage = exception?.message

                        val errorMsg = """
            Connection failed
            Type: ${exception?.javaClass?.simpleName}
            ReasonCode: $reasonCode
            Message: $exceptionMessage
            Cause: $causeMessage
            Broker: $brokerUrl
        """.trimIndent()

                        ui?.showMessage(errorMsg)
                    }
                }
            })
        } catch (e: Exception) {
            handler.post {
                ui?.showMessage("MQTT connect error: ${e.message}")
            }
        }
    }

    private fun handleCommand(payload: String) {
        try {
            val json = JSONObject(payload)

            if (json.optString("action") != "show_folder") return

            val baseUrl = json.optString("baseUrl").trim()
            val folder = json.optString("folder").trim()
            val fallbackFolder = json.optString("fallbackFolder", "basics").trim()
            val startTime = json.optString("startTime").trim()
            val endTime = json.optString("endTime").trim()

            delayMs = json.optInt("carouselDelaySeconds", 5).coerceAtLeast(1) * 1000L

            if (baseUrl.isBlank()) {
                handler.post { ui?.showMessage("Base URL is empty") }
                return
            }

            if (folder.isBlank()) {
                handler.post { ui?.showMessage("Folder is empty") }
                return
            }

            val selectedFolder = if (isNowBetween(startTime, endTime)) {
                folder
            } else {
                fallbackFolder
            }

            loadFolder(baseUrl, selectedFolder)
            scheduleFallback(baseUrl, fallbackFolder, endTime)

        } catch (e: Exception) {
            handler.post {
                ui?.showMessage("Command error: ${e.message}")
            }
        }
    }

    private fun loadFolder(baseUrl: String, folder: String) {
        Thread {
            try {
                val cleanBaseUrl = baseUrl.removeSuffix("/")
                val cleanFolder = folder.trim('/')

                val manifestUrl = "$cleanBaseUrl/$cleanFolder/manifest.json"
                val manifest = downloadText(manifestUrl)
                val array = JSONArray(manifest)

                val urls = mutableListOf<String>()

                for (i in 0 until array.length()) {
                    val fileName = array.getString(i)
                    urls.add("$cleanBaseUrl/$cleanFolder/$fileName")
                }

                images = urls
                index = 0

                handler.post {
                    startCarousel()
                }

            } catch (e: Exception) {
                handler.post {
                    ui?.showMessage("Load error: ${e.message}")
                }
            }
        }.start()
    }

    private fun startCarousel() {
        carouselRunnable?.let {
            handler.removeCallbacks(it)
        }

        if (images.isEmpty()) {
            ui?.showMessage("No images")
            return
        }

        showImage(images[index])

        if (images.size > 1) {
            carouselRunnable = object : Runnable {
                override fun run() {
                    index = (index + 1) % images.size
                    showImage(images[index])
                    handler.postDelayed(this, delayMs)
                }
            }

            handler.postDelayed(carouselRunnable!!, delayMs)
        }
    }

    private fun showImage(url: String) {
        Thread {
            try {
                val bitmap = downloadBitmap(url)

                val matrix = Matrix().apply { postRotate(270f) }
                val rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )

                handler.post {
                    ui?.showImage(rotatedBitmap)
                }

            } catch (e: Exception) {
                handler.post {
                    ui?.showMessage("Image error: ${e.message}")
                }
            }
        }.start()
    }

    private fun scheduleFallback(baseUrl: String, fallbackFolder: String, endTime: String) {
        fallbackRunnable?.let {
            handler.removeCallbacks(it)
        }

        if (endTime.isBlank()) return

        val delay = delayTo(endTime)

        if (delay <= 0) {
            loadFolder(baseUrl, fallbackFolder)
            return
        }

        fallbackRunnable = Runnable {
            loadFolder(baseUrl, fallbackFolder)
        }

        handler.postDelayed(fallbackRunnable!!, delay)
    }

    private fun downloadText(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        return connection.inputStream.bufferedReader().use {
            it.readText()
        }
    }

    private fun downloadBitmap(urlString: String): Bitmap {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 7000

        return BitmapFactory.decodeStream(connection.inputStream)
    }

    private fun isNowBetween(start: String, end: String): Boolean {
        if (start.isBlank() || end.isBlank()) return true

        return try {
            val format = SimpleDateFormat("HH:mm", Locale.US)

            val now = format.parse(format.format(Date()))
            val startDate = format.parse(start)
            val endDate = format.parse(end)

            if (now == null || startDate == null || endDate == null) {
                true
            } else {
                now.time >= startDate.time && now.time <= endDate.time
            }

        } catch (_: Exception) {
            true
        }
    }

    private fun delayTo(time: String): Long {
        return try {
            val parts = time.split(":")
            if (parts.size != 2) return -1

            val hour = parts[0].toInt()
            val minute = parts[1].toInt()

            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            target.timeInMillis - System.currentTimeMillis()

        } catch (_: Exception) {
            -1
        }
    }

    override fun onCleared() {
        carouselRunnable?.let {
            handler.removeCallbacks(it)
        }

        fallbackRunnable?.let {
            handler.removeCallbacks(it)
        }

        try {
            mqttClient?.disconnect()
            mqttClient?.unregisterResources()
            mqttClient?.close()
        } catch (_: Exception) {
        }

        super.onCleared()
    }
}