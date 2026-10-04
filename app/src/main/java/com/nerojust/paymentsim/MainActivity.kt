package com.nerojust.paymentsim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nerojust.paymentsim.ui.SimulatorScreen
import com.nerojust.paymentsim.ui.theme.PaymentSimTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaymentSimTheme {
                SimulatorScreen()
            }
        }
    }
}
