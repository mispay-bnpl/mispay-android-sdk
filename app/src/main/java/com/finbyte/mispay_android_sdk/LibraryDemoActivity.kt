package com.finbyte.mispay_android_sdk

import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

import android.widget.Button
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.finbyte.mispaysdk.MISPay


class LibraryDemoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 3)
        }

        val button: Button = findViewById(R.id.button)
        button.setOnClickListener{
            MISPay.checkout(this, "https://mispay-checkout-dev.finbyte.cloud/") { token ->
                button.text = token
            }
        }

    }
}