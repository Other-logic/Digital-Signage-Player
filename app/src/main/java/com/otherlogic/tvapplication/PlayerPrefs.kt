package com.otherlogic.tvapplication

import android.content.Context

class PlayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    fun getBrokerUrl() = prefs.getString("broker_url", "") ?: ""
    fun getDeviceId() = prefs.getString("device_id", "") ?: ""

    fun saveSettings(brokerUrl: String, deviceId: String) {
        prefs.edit()
            .putString("broker_url", brokerUrl)
            .putString("device_id", deviceId)
            .apply()
    }

    fun isConfigured(): Boolean {
        return getBrokerUrl().isNotBlank() && getDeviceId().isNotBlank()
    }
}