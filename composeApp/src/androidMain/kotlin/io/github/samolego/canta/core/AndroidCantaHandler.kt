package io.github.samolego.canta.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.samolego.canta.core.shizuku.ShizukuPermission
import io.github.samolego.canta.core.shizuku.ShizukuServices
import io.github.samolego.canta.data.app.AppInfo
import io.github.samolego.canta.packages.OperationResult
import io.github.samolego.canta.packages.PackageDetails
import io.github.samolego.canta.packages.PackageOperations
import io.github.samolego.canta.packages.canResetToFactory
import io.github.samolego.canta.packages.getAllPackages
import io.github.samolego.canta.packages.getInfoForPackage
import io.github.samolego.canta.packages.inDisplayOrder
import io.github.samolego.canta.packages.loadIconBitmap
import io.github.samolego.canta.packages.readSafely
import io.github.samolego.canta.packages.toPackageDetails
import io.github.samolego.canta.ui.component.APP_ICON_SIZE
import io.github.samolego.canta.util.LogUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Android handler with Shizuku as the default backend and Wireless ADB as an alternative. */
class AndroidCantaHandler(private val context: Context) : CantaHandler {
    private companion object {
        const val TAG = "AndroidCantaHandler"
    }

    private val packageManager: PackageManager get() = context.packageManager
    private val operations by lazy { PackageOperations(ShizukuServices, context) }
    private val wirelessAdb = AndroidWirelessAdbHandler(context)
    private val packageDispatcher = Dispatchers.IO.limitedParallelism(Runtime.getRuntime().availableProcessors())
    private val iconSizePx: Int
        get() = (APP_ICON_SIZE.value * context.resources.displayMetrics.density).roundToInt()

    @Volatile
    private var packages: List<Pair<PackageDetails, ApplicationInfo?>> = emptyList()

    override val isAuthorized: Boolean
        get() = wirelessAdb.isAuthorized || ShizukuPermission.isCantaAuthorized()

    override val privilegeStatus: PrivilegeStatus
        get() = if (wirelessAdb.isAuthorized) PrivilegeStatus.ACTIVE
        else ShizukuPermission.privilegeStatus(packageManager)

    suspend fun pairWirelessAdb(host: String, pairingPort: Int, pairingCode: String, connectPort: Int): Boolean =
        wirelessAdb.pairAndConnect(host, pairingPort, pairingCode, connectPort)

    fun wirelessAdbError(): String? = wirelessAdb.lastWirelessError

    override suspend fun loadApps(): List<AppInfo> {
        if (wirelessAdb.isAuthorized) return wirelessAdb.loadApps()
        return withContext(packageDispatcher) {
            packages = packageManager.getAllPackages()
                .map { info -> async { readSafely(info) { info.toPackageDetails(packageManager) to info.applicationInfo } } }
                .awaitAll()
                .filterNotNull()
            packages.map { (details, _) -> AppInfo(details) }
        }
    }

    override fun loadIcons(): Flow<Pair<String, ImageBitmap>> {
        if (wirelessAdb.isAuthorized) return wirelessAdb.loadIcons()
        return flow {
            val appInfos = packages.associate { (details, appInfo) -> details.packageName to appInfo }
            val sizePx = iconSizePx
            coroutineScope {
                val icons = packages.map { it.first }.inDisplayOrder().map { details ->
                    async(packageDispatcher) {
                        appInfos[details.packageName]
                            ?.let { packageManager.loadIconBitmap(it, sizePx) }
                            ?.let { details.packageName to it.asImageBitmap() }
                    }
                }
                icons.forEach { icon -> icon.await()?.let { emit(it) } }
            }
        }
    }

    override suspend fun canResetToFactory(packageName: String): Boolean =
        if (wirelessAdb.isAuthorized) wirelessAdb.canResetToFactory(packageName)
        else packageManager.getInfoForPackage(packageName)?.applicationInfo?.canResetToFactory == true

    override fun uninstallApps(packageNames: List<String>, resetToFactory: Boolean): Flow<OperationResult> =
        if (wirelessAdb.isAuthorized) wirelessAdb.uninstallApps(packageNames, resetToFactory)
        else flow { packageNames.forEach { emit(operations.uninstall(it, resetToFactory)) } }.flowOn(Dispatchers.IO)

    override fun reinstallApps(packageNames: List<String>): Flow<OperationResult> =
        if (wirelessAdb.isAuthorized) wirelessAdb.reinstallApps(packageNames)
        else flow { packageNames.forEach { emit(operations.reinstall(it)) } }.flowOn(Dispatchers.IO)

    override suspend fun requestAuthorization() {
        if (wirelessAdb.isAuthorized) return
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            ShizukuPermission.requestShizukuPermission { result ->
                deferred.complete(result == PackageManager.PERMISSION_GRANTED)
            }
        }
        deferred.await()
    }

    override suspend fun packageExists(packageName: String): Boolean =
        if (wirelessAdb.isAuthorized) wirelessAdb.packageExists(packageName)
        else packageManager.getInfoForPackage(packageName) != null

    override suspend fun openAppDetails(packageName: String) {
        if (wirelessAdb.isAuthorized) {
            wirelessAdb.openAppDetails(packageName)
            return
        }
        try {
            val uri = Uri.fromParts("package", packageName, null)
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to open app details for '$packageName'", e)
        }
    }
}
