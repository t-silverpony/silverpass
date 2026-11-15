package com.silverpass.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.silverpass.mobile.ui.MobileApp

class MobileActivity : ComponentActivity() {
    private val viewModel: MobileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.back()) finish()
            }
        })
        setContent { MobileApp(viewModel) }
    }

    // 다른 기기(차량·가족 휴대전화)에서 바뀐 값을 화면에 다시 올 때마다 받아 온다.
    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
