package com.yingji.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yingji.app.domain.model.ThemeMode
import com.yingji.app.navigation.YingJiNavGraph
import com.yingji.app.ui.theme.YingJiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by (application as YingJiApplication)
                .themeRepository
                .themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            YingJiTheme(themeMode = themeMode) {
                YingJiNavGraph()
            }
        }
    }
}
