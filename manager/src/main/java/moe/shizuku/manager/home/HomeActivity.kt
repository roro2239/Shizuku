package moe.shizuku.manager.home

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AboutDialogBinding
import moe.shizuku.manager.databinding.HomeActivityBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.management.appsViewModel
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.utils.AppIconCache
import rikka.core.ktx.unsafeLazy
import rikka.lifecycle.Status
import rikka.lifecycle.viewModels
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import roro.stellar.Stellar
import android.util.Log
import moe.shizuku.manager.AppConstants

abstract class HomeActivity : AppBarActivity() {

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkServerStatus()
        appsModel.load()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        checkServerStatus()
    }

    // Stellar 监听器
    private val stellarBinderReceivedListener = Stellar.OnBinderReceivedListener {
        Log.i(AppConstants.TAG, "Stellar 服务已连接")
        checkStellarStatus()
    }

    private val stellarBinderDeadListener = Stellar.OnBinderDeadListener {
        Log.w(AppConstants.TAG, "Stellar 服务已断开")
    }

    private val stellarPermissionResultListener =
        Stellar.OnRequestPermissionResultListener { requestCode, allowed, onetime ->
            if (allowed) {
                Log.i(AppConstants.TAG, "Stellar 权限已授予 (requestCode: $requestCode)")
                // 当所有权限授予后，自动激活服务
                checkAndActivateService()
            } else {
                Log.w(AppConstants.TAG, "Stellar 权限被拒绝 (requestCode: $requestCode)")
            }
        }

    private val homeModel by viewModels { HomeViewModel() }
    private val appsModel by appsViewModel()
    private val adapter by unsafeLazy { HomeAdapter(homeModel, appsModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = HomeActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        homeModel.serviceStatus.observe(this) {
            if (it.status == Status.SUCCESS) {
                val status = homeModel.serviceStatus.value?.data ?: return@observe
                adapter.updateData()
                ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
            }
        }
        appsModel.grantedCount.observe(this) {
            if (it.status == Status.SUCCESS) {
                adapter.updateData()
            }
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addItemSpacing(top = 4f, bottom = 4f, unit = TypedValue.COMPLEX_UNIT_DIP)
        recyclerView.addEdgeSpacing(top = 4f, bottom = 4f, left = 16f, right = 16f, unit = TypedValue.COMPLEX_UNIT_DIP)

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 添加 Stellar 监听器
        Stellar.addBinderReceivedListenerSticky(stellarBinderReceivedListener)
        Stellar.addBinderDeadListener(stellarBinderDeadListener)
        Stellar.addRequestPermissionResultListener(stellarPermissionResultListener)
    }

    override fun onResume() {
        super.onResume()
        checkServerStatus()
    }

    private fun checkServerStatus() {
        homeModel.reload()
    }

    private fun checkStellarStatus() {
        try {
            if (!Stellar.pingBinder()) {
                Log.w(AppConstants.TAG, "Stellar 服务未运行")
                return
            }

            Log.i(AppConstants.TAG, "Stellar 服务已连接")
            Log.i(AppConstants.TAG, "Stellar 服务版本: ${Stellar.version}")
            Log.i(AppConstants.TAG, "Stellar 服务 UID: ${Stellar.uid}")

            // 请求所有必需的权限
            requestAllStellarPermissions()

            // 检查并激活服务
            checkAndActivateService()
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "检查 Stellar 状态时出错", e)
        }
    }

    private fun requestAllStellarPermissions() {
        try {
            // 请求基础权限
            if (!Stellar.checkSelfPermission()) {
                Log.i(AppConstants.TAG, "请求 Stellar 基础权限")
                Stellar.requestPermission(requestCode = 1)
                return
            }

            // 请求 follow_stellar_startup 权限
            if (!Stellar.checkSelfPermission("follow_stellar_startup")) {
                Log.i(AppConstants.TAG, "请求 follow_stellar_startup 权限")
                Stellar.requestPermission("follow_stellar_startup", 2)
                return
            }

            Log.i(AppConstants.TAG, "所有 Stellar 权限已授予")
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "请求 Stellar 权限时出错", e)
        }
    }

    private fun checkAndActivateService() {
        try {
            // 检查是否所有权限都已授予
            if (!Stellar.checkSelfPermission() ||
                !Stellar.checkSelfPermission("follow_stellar_startup")) {
                Log.i(AppConstants.TAG, "等待所有 Stellar 权限授予")
                return
            }

            // 检查 Shizuku 服务是否已经在运行
            if (Shizuku.pingBinder()) {
                Log.i(AppConstants.TAG, "Shizuku 服务已在运行")
                return
            }

            // 自动激活 Shizuku 服务
            Log.i(AppConstants.TAG, "Shizuku 服务未运行，正在自动激活...")
            activateShizukuService()
        } catch (e: Exception) {
            Log.e(AppConstants.TAG, "检查并激活服务时出错", e)
        }
    }

    private fun activateShizukuService() {
        // 在后台线程中执行启动操作
        Thread {
            try {
                // 构建启动命令
                val starter = moe.shizuku.manager.starter.Starter.internalCommand
                Log.i(AppConstants.TAG, "启动命令: $starter")

                // 使用 Stellar 的权限执行启动命令
                val startProcess = Stellar.newProcess(
                    arrayOf("sh", "-c", starter),
                    null,
                    null
                )

                // 读取输出
                val output = startProcess.inputStream.bufferedReader().readText()
                val error = startProcess.errorStream.bufferedReader().readText()

                val exitCode = startProcess.waitFor()
                startProcess.destroy()

                if (output.isNotEmpty()) {
                    Log.i(AppConstants.TAG, "启动输出: $output")
                }
                if (error.isNotEmpty()) {
                    Log.e(AppConstants.TAG, "启动错误: $error")
                }

                if (exitCode == 0) {
                    Log.i(AppConstants.TAG, "Shizuku 服务已成功激活")
                    // 延迟检查服务状态
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        checkServerStatus()
                    }, 1000)
                } else {
                    Log.e(AppConstants.TAG, "Shizuku 服务激活失败，退出码: $exitCode")
                }
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "激活 Shizuku 服务时出错", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)

        // 移除 Stellar 监听器
        Stellar.removeBinderReceivedListener(stellarBinderReceivedListener)
        Stellar.removeBinderDeadListener(stellarBinderDeadListener)
        Stellar.removeRequestPermissionResultListener(stellarPermissionResultListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

}
