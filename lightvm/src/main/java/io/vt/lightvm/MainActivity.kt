package io.vt.lightvm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var state by remember { mutableStateOf("未安装 rootfs") }
                LaunchedEffect(Unit) {
                    if (!VmManager.isInstalled(this@MainActivity)) {
                        state = "自动安装 rootfs…"
                        withContext(Dispatchers.IO) {
                            VmManager.installRootfs(this@MainActivity) { msg ->
                                runOnUiThread { state = msg }
                            }
                        }
                        state = if (VmManager.isInstalled(this@MainActivity)) "rootfs 已安装" else state
                    } else {
                        state = "rootfs 已安装"
                    }
                    if (VmManager.isInstalled(this@MainActivity) &&
                        VmManager.prepareBoot(this@MainActivity) { state = it }
                    ) {
                        delay(800)
                        startActivity(Intent(this@MainActivity, VmRenderActivity::class.java))
                    }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("VT LightVM", style = MaterialTheme.typography.headlineMedium)
                        Text(state)
                        Button(onClick = {
                            VmManager.installRootfs(this@MainActivity) { state = it }
                            state = if (VmManager.isInstalled(this@MainActivity)) "rootfs 已安装" else state
                        }) { Text("安装 rootfs") }
                        Button(onClick = {
                            if (VmManager.prepareBoot(this@MainActivity) { state = it }) {
                                startActivity(
                                    Intent(this@MainActivity, VmRenderActivity::class.java)
                                )
                            }
                        }) { Text("启动虚拟机") }
                        Button(onClick = {
                            finishAffinity()
                        }) { Text("停止虚拟机") }
                        Button(onClick = {
                            startActivity(Intent(this@MainActivity, VmRenderActivity::class.java))
                        }) { Text("打开渲染窗口") }
                        Button(onClick = {
                            VmManager.installApk(
                                this@MainActivity,
                                File(filesDir, "app.apk")
                            ) { state = it }
                        }) { Text("安装 APK（占位）") }
                    }
                }
            }
        }
    }
}
