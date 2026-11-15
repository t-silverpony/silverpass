package com.silverpass.vehicle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.silverpass.vehicle.ui.VehicleApp

class VehicleActivity : ComponentActivity() {
    private val viewModel: VehicleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VehicleApp(viewModel) }
    }
}
