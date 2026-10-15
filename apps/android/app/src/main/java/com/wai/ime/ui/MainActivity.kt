package com.wai.ime.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.wai.ime.R

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        root.addView(titleView)

        val descView = TextView(this).apply {
            text = getString(R.string.welcome_hint)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }
        root.addView(descView)

        val btnEnable = Button(this).apply {
            text = getString(R.string.btn_enable_ime)
            setOnClickListener {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                startActivity(intent)
            }
        }
        root.addView(btnEnable)

        val btnSelect = Button(this).apply {
            text = getString(R.string.btn_select_ime)
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
        root.addView(btnSelect)

        setContentView(root)
    }
}
