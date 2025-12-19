package moe.shizuku.manager.home

import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool

class HomeAdapter(private val homeModel: HomeViewModel, private val appsModel: AppsViewModel) :
    IdBasedRecyclerViewAdapter(ArrayList()) {

    init {
        updateData()
        setHasStableIds(true)
    }

    companion object {

        private const val ID_STELLAR_STATUS = 0L
        private const val ID_STATUS = 1L
        private const val ID_STOP_SERVICE = 2L
        private const val ID_APPS = 3L
        private const val ID_TERMINAL = 4L
        private const val ID_ADB_PERMISSION_LIMITED = 7L
    }

    override fun onCreateCreatorPool(): IndexCreatorPool {
        return IndexCreatorPool()
    }

    fun updateData() {
        val status = homeModel.serviceStatus.value?.data ?: return
        val grantedCount = appsModel.grantedCount.value?.data ?: 0
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0

        clear()

        // 添加 Stellar 服务状态
        addItem(StellarStatusViewHolder.CREATOR, Unit, ID_STELLAR_STATUS)

        // 添加 Shizuku 服务状态
        addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS)

        // 添加停止服务卡片
        addItem(StopServiceViewHolder.CREATOR, status, ID_STOP_SERVICE)

        if (adbPermission) {
            addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS)
            addItem(TerminalViewHolder.CREATOR, status, ID_TERMINAL)
        }

        if (running && !adbPermission) {
            addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED)
        }

        notifyDataSetChanged()
    }
}
