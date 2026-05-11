package com.otherlogic.tvapplication

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.otherlogic.tvapplication.PlayerViewModel

import android.view.View

class MainActivity : AppCompatActivity(), PlayerViewModel.Ui {

    private lateinit var viewModel: PlayerViewModel
    private var imageView: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUI()

        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        viewModel.attach(this)
        viewModel.start()
    }

    private fun hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun showSetup() {
        runOnUiThread {
            setContentView(R.layout.activity_player_setup)

            val brokerEdit = findViewById<EditText>(R.id.brokerEditText)
            val deviceEdit = findViewById<EditText>(R.id.deviceIdEditText)
            val saveButton = findViewById<Button>(R.id.saveButton)

            saveButton.setOnClickListener {
                viewModel.saveSettingsAndConnect(
                    brokerEdit.text.toString(),
                    deviceEdit.text.toString()
                )
            }
        }
    }

    override fun showPlayer(deviceId: String) {
        runOnUiThread {
            setContentView(R.layout.activity_player)

            imageView = findViewById(R.id.imageView)
            findViewById<TextView>(R.id.statusText).text = "Device: $deviceId"
        }
    }

    override fun showImage(bitmap: Bitmap) {
        runOnUiThread {
            imageView?.setImageBitmap(bitmap)
        }
    }

    override fun showMessage(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

}