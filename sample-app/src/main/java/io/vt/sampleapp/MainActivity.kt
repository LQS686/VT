package io.vt.sampleapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private var remoteConnection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        current = this
        findViewById<TextView>(R.id.message).text = getMessage()
        findViewById<TextView>(R.id.app_tag).text = (application as SampleApp).appTag()
        findViewById<TextView>(R.id.open_count).text = SampleApp.openCountText() +
            "，数据目录启动次数：${SampleApp.launchCount}" +
            "，版本：${packageManager.getPackageInfo(packageName, 0).versionName}"
        val providerQuery = try {
            contentResolver.query(
                Uri.parse("content://io.vt.sampleapp.provider/ping"), null, null, null, null
            )?.use { it.count } ?: -1
        } catch (t: Throwable) {
            Log.e("VTSample", "provider query failed", t)
            -1
        }
        val serviceComponent = try {
            startService(Intent(this, SampleService::class.java))
        } catch (t: Throwable) {
            Log.e("VTSample", "startService failed", t)
            null
        }
        val metaValue = try {
            packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getString("io.vt.sampleapp.key")
        } catch (t: Throwable) {
            "meta-failed"
        }
        val signerCount = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo?.apkContentsSigners?.size ?: -1
            } else -2
        } catch (t: Throwable) {
            Log.e("VTSample", "signing info failed", t)
            -3
        }
        val broadcastResult = try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    SampleApp.broadcastCount += 1
                    Log.i("VTSample", "broadcast received count=${SampleApp.broadcastCount}")
                }
            }
            registerReceiver(receiver, IntentFilter("io.vt.sampleapp.PING"))
            sendBroadcast(Intent("io.vt.sampleapp.PING"))
            sendBroadcast(Intent("io.vt.sampleapp.STATIC"))
            "registered"
        } catch (t: Throwable) {
            Log.e("VTSample", "broadcast failed", t)
            "failed: ${t.javaClass.simpleName}"
        }
        val nativeResult = try {
            NativeBridge.greeting()
        } catch (t: Throwable) {
            Log.e("VTSample", "native load failed", t)
            "native-failed: ${t.javaClass.simpleName}"
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val result = try {
                    (binder as? SampleService.LocalBinder)?.ping()
                } catch (t: Throwable) {
                    null
                }
                SampleApp.boundResult = result
                Log.i("VTSample", "service connected: $name result=$result")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                SampleApp.boundResult = null
            }
        }
        val bound = try {
            bindService(Intent(this, SampleService::class.java), connection, BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.e("VTSample", "bindService failed", t)
            false
        }
        findViewById<TextView>(R.id.provider_state).text =
            "providerReady=${SampleApp.providerInitialized}, queryCount=$providerQuery, " +
            "serviceStarted=${SampleApp.serviceStarted}, startResult=${serviceComponent?.className}, " +
            "native=$nativeResult, bound=$bound, boundResult=${SampleApp.boundResult}, " +
            "broadcast=$broadcastResult/${SampleApp.broadcastCount}, static=${SampleApp.staticCount}, " +
            "meta=$metaValue, signers=$signerCount"
        findViewById<Button>(R.id.open_second).setOnClickListener {
            startActivity(Intent(this, SecondActivity::class.java))
        }
        findViewById<Button>(R.id.test_foreground).setOnClickListener {
            try {
                val intent = Intent(this, SampleService::class.java)
                    .setAction(SampleService.ACTION_FOREGROUND)
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                findViewById<TextView>(R.id.notification_state).text =
                    "前台通知已请求：started=${SampleApp.foregroundStarted}, " +
                        "error=${SampleApp.foregroundError}"
            } catch (t: Throwable) {
                findViewById<TextView>(R.id.notification_state).text = "前台通知失败：$t"
            }
        }
        findViewById<Button>(R.id.stop_foreground).setOnClickListener {
            try {
                startService(
                    Intent(this, SampleService::class.java)
                        .setAction(SampleService.ACTION_STOP_FOREGROUND)
                )
                findViewById<TextView>(R.id.notification_state).text =
                    "停止前台通知：stopped=${SampleApp.foregroundStopped}"
            } catch (t: Throwable) {
                findViewById<TextView>(R.id.notification_state).text = "停止前台通知失败：$t"
            }
        }
        findViewById<Button>(R.id.open_remote).setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
        }
        findViewById<Button>(R.id.test_result).setOnClickListener {
            startActivityForResult(
                Intent(this, ResultActivity::class.java),
                1001
            )
        }
        findViewById<Button>(R.id.remote_service).setOnClickListener {
            val state = findViewById<TextView>(R.id.notification_state)
            startService(Intent(this, SampleRemoteService::class.java))
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val result = try {
                        IRemotePing.Stub.asInterface(binder)?.ping() ?: "null-binder"
                    } catch (t: Throwable) {
                        "error:${t.javaClass.simpleName}:${t.message}"
                    }
                    Log.i("VTSample", "remote service connected: $result")
                    runOnUiThread { state.text = "跨进程服务：$result" }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.i("VTSample", "remote service disconnected")
                    runOnUiThread { state.text = "跨进程服务已断开" }
                }
            }
            remoteConnection = connection
            val bound = try {
                bindService(
                    Intent(this, SampleRemoteService::class.java),
                    connection,
                    BIND_AUTO_CREATE
                )
            } catch (t: Throwable) {
                Log.e("VTSample", "remote bindService failed", t)
                false
            }
            state.text = "跨进程绑定请求：bound=$bound"
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.open_count).text = SampleApp.openCountText() +
            "，数据目录启动次数：${SampleApp.launchCount}" +
            "，版本：${packageManager.getPackageInfo(packageName, 0).versionName}"
        val providerQuery = try {
            contentResolver.query(
                Uri.parse("content://io.vt.sampleapp.provider/ping"), null, null, null, null
            )?.use { it.count } ?: -1
        } catch (t: Throwable) {
            Log.e("VTSample", "provider query failed", t)
            -1
        }
        val serviceComponent = try {
            startService(Intent(this, SampleService::class.java))
        } catch (t: Throwable) {
            Log.e("VTSample", "startService failed", t)
            null
        }
        val metaValue = try {
            packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getString("io.vt.sampleapp.key")
        } catch (t: Throwable) {
            "meta-failed"
        }
        val signerCount = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                ).signingInfo?.apkContentsSigners?.size ?: -1
            } else -2
        } catch (t: Throwable) {
            Log.e("VTSample", "signing info failed", t)
            -3
        }
        val broadcastResult = try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    SampleApp.broadcastCount += 1
                    Log.i("VTSample", "broadcast received count=${SampleApp.broadcastCount}")
                }
            }
            registerReceiver(receiver, IntentFilter("io.vt.sampleapp.PING"))
            sendBroadcast(Intent("io.vt.sampleapp.PING"))
            sendBroadcast(Intent("io.vt.sampleapp.STATIC"))
            "registered"
        } catch (t: Throwable) {
            Log.e("VTSample", "broadcast failed", t)
            "failed: ${t.javaClass.simpleName}"
        }
        val nativeResult = try {
            NativeBridge.greeting()
        } catch (t: Throwable) {
            Log.e("VTSample", "native load failed", t)
            "native-failed: ${t.javaClass.simpleName}"
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val result = try {
                    (binder as? SampleService.LocalBinder)?.ping()
                } catch (t: Throwable) {
                    null
                }
                SampleApp.boundResult = result
                Log.i("VTSample", "service connected: $name result=$result")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                SampleApp.boundResult = null
            }
        }
        val bound = try {
            bindService(Intent(this, SampleService::class.java), connection, BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.e("VTSample", "bindService failed", t)
            false
        }
        findViewById<TextView>(R.id.provider_state).text =
            "providerReady=${SampleApp.providerInitialized}, queryCount=$providerQuery, " +
            "serviceStarted=${SampleApp.serviceStarted}, startResult=${serviceComponent?.className}, " +
            "native=$nativeResult, bound=$bound, boundResult=${SampleApp.boundResult}, " +
            "broadcast=$broadcastResult/${SampleApp.broadcastCount}, static=${SampleApp.staticCount}, " +
            "meta=$metaValue, signers=$signerCount"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            val value = data?.getStringExtra("vt_result")
            findViewById<TextView>(R.id.notification_state).text =
                "Activity 结果: $value (resultCode=$resultCode)"
        }
    }

    override fun onDestroy() {
        remoteConnection?.let { runCatching { unbindService(it) } }
        remoteConnection = null
        super.onDestroy()
    }

    companion object {
        @JvmField
        var current: MainActivity? = null

        @JvmStatic
        @Synchronized
        fun getMessage(): String = "原始消息：VT Guest 正常启动"
    }
}
