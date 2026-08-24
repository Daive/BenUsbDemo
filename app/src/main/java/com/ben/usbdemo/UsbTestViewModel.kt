package com.ben.usbdemo

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ben.libbenusb.BenUsbDevice
import com.ben.libbenusb.FileSystem
import com.ben.libbenusb.UsbFile
import com.ben.libbenusb.UsbFileOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class UsbTestStatus {
    INIT,
    TESTING,
    SUCCESS,
    FAILED,
}

data class AppLog(
    val time: String,
    val message: String,
)

/** 单次测试的统计记录（第三列展示） */
data class TestRecord(
    val time: String,
    val deviceName: String,
    val fileSystemType: String,
    val usbVersion: String,
    val speedText: String,
    val result: String,
)

private data class TestOutcome(
    val summary: String,
    val detail: List<String>,
    val speedText: String,
)

class UsbDeviceItem(
    val massDevice: BenUsbDevice,
    val usbDevice: UsbDevice,
    name: String,
) {
    var name by mutableStateOf(name)
    var status by mutableStateOf(UsbTestStatus.INIT)
    var message by mutableStateOf("")
    var manufacturer by mutableStateOf("")
    var fileSystemType by mutableStateOf("")
    var capacityText by mutableStateOf("")
    var usbVersionText by mutableStateOf("")
}

class UsbTestViewModel(application: Application) : AndroidViewModel(application) {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val devices = mutableStateListOf<UsbDeviceItem>()
    val appLogs = mutableStateListOf<AppLog>()
    val testRecords = mutableStateListOf<TestRecord>()

    init {
        // 首次打开：确保测试文件已从 assets 写入内部存储（异步准备，U 盘插入后即可使用）
        viewModelScope.launch(Dispatchers.IO) {
            ensureTestFile()
        }
    }

    /**
     * 确保 c38-firmware.zip 已复制到内部存储（打包在 assets 中）。
     */
    private fun ensureTestFile(): File {
        val app = getApplication<Application>()
        val target = File(File(app.filesDir, TEST_FILE_DIR), TEST_ZIP_NAME)
        if (!target.exists() || target.length() == 0L) {
            target.parentFile?.mkdirs()
            app.assets.open("$TEST_FILE_DIR/$TEST_ZIP_NAME").use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            }
            Log.d(TAG, "测试文件已写入内部存储：${target.absolutePath} (${target.length()} bytes)")
        }
        return target
    }

    fun addDevice(massDevice: BenUsbDevice) {
        val usbDevice = massDevice.usbDevice
        if (devices.any { it.usbDevice.deviceId == usbDevice.deviceId }) return

        val displayName = usbDevice.productName?.takeIf { it.isNotBlank() }
            ?: "USB 设备 (${usbDevice.deviceId})"
        addLog("检测到 U 盘插入：$displayName")

        val item = UsbDeviceItem(massDevice, usbDevice, displayName)
        // 部分 U 盘描述符未提供制造商字符串（如 HIKSEMI），此时回退用产品名作为品牌
        item.manufacturer = usbDevice.manufacturerName?.takeIf { it.isNotBlank() }
            ?: usbDevice.productName?.takeIf { it.isNotBlank() }
            ?: "未知"
        devices.add(item)

        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                item.usbVersionText = queryUsbVersion(usbDevice)
                runCatching { massDevice.init() }.exceptionOrNull()
            }
            if (error != null) {
                item.status = UsbTestStatus.FAILED
                item.message = "初始化失败：${error.message ?: error.javaClass.simpleName}"
                addLog("${item.name} 初始化失败：${error.message ?: error.javaClass.simpleName}")
                return@launch
            }
            updateDeviceInfo(item)
            addLog("${item.name} 初始化完成（${item.fileSystemType} ${item.capacityText}）")
            startTest(item)
        }
    }

    fun removeDevice(usbDevice: UsbDevice) {
        val item = devices.firstOrNull { it.usbDevice.deviceId == usbDevice.deviceId } ?: return
        devices.remove(item)
        addLog("${item.name} 已拔出")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { item.massDevice.close() }
        }
    }

    fun testDevice(item: UsbDeviceItem) {
        startTest(item)
    }

    private fun startTest(item: UsbDeviceItem) {
        if (item.status == UsbTestStatus.TESTING) return
        item.status = UsbTestStatus.TESTING
        item.message = ""
        addLog("${item.name} 测试开始")
        viewModelScope.launch {
            delay(100)
            val result = withContext(Dispatchers.IO) {
                runCatching { performTest(item.massDevice) }
            }
            result.onSuccess { outcome ->
                item.status = UsbTestStatus.SUCCESS
                item.message = outcome.summary
                outcome.detail.forEach { addLog("${item.name} $it") }
                addLog("${item.name} 测试结束：成功")
                testRecords.add(
                    TestRecord(
                        time = now(),
                        deviceName = item.name,
                        fileSystemType = item.fileSystemType,
                        usbVersion = item.usbVersionText,
                        speedText = outcome.speedText,
                        result = "成功"
                    )
                )
            }.onFailure { e ->
                Log.e(TAG, "测试失败，完整堆栈：", e)
                item.status = UsbTestStatus.FAILED
                item.message = e.message ?: e.javaClass.simpleName
                addLog("${item.name} 测试结束：失败（${item.message}）")
                testRecords.add(
                    TestRecord(
                        time = now(),
                        deviceName = item.name,
                        fileSystemType = item.fileSystemType,
                        usbVersion = item.usbVersionText,
                        speedText = "-",
                        result = "失败：${item.message}"
                    )
                )
            }
        }
    }

    private fun updateDeviceInfo(item: UsbDeviceItem) {
        runCatching {
            val fs = item.massDevice.fileSystem
            if (fs != null) {
                item.fileSystemType = item.massDevice.fileSystemTypeName ?: "未知"
                item.capacityText = formatBytes(fs.capacity)
            }
        }
        if (item.fileSystemType.isBlank()) item.fileSystemType = "未知"
        if (item.capacityText.isBlank()) item.capacityText = "未知"
        item.name = resolveName(item)
    }

    private fun resolveName(item: UsbDeviceItem): String {
        item.usbDevice.productName?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching {
            item.massDevice.partitions.firstOrNull()?.volumeLabel?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }
        return "USB 设备 (${item.usbDevice.deviceId})"
    }

    private fun performTest(massDevice: BenUsbDevice): TestOutcome {
        val fileSystem = massDevice.fileSystem
            ?: throw IllegalStateException("未找到可用文件系统")
        val root = fileSystem.rootDirectory

        val testDir = root.listFiles()
            .firstOrNull { it.isDirectory && it.name == TEST_DIR_NAME }
            ?: root.createDirectory(TEST_DIR_NAME)

        // 确保测试 zip 在内部存储（首次启动的异步复制可能未完成，此处同步兜底）
        val zipSource = ensureTestFile()

        // 1. 写入 zip（速度测试）
        val tPre = System.currentTimeMillis()
        val zipUsb: UsbFile = testDir.createFile(TEST_ZIP_NAME)
        zipUsb.length = zipSource.length()
        Log.d(TAG, "setLength 预分配耗时：${System.currentTimeMillis() - tPre}ms")
        val start = System.currentTimeMillis()
        val buffer = ByteArray(1024 * 1024) // 1MB，保证整簇写入
        zipSource.inputStream().use { input ->
            val os = UsbFileOutputStream(zipUsb)
            try {
                var count = input.read(buffer)
                while (count > 0) {
                    os.write(buffer, 0, count)
                    count = input.read(buffer)
                }
            } finally {
                os.close()
            }
        }
        val elapsed = System.currentTimeMillis() - start
        val speed = if (elapsed > 0) {
            zipSource.length().toDouble() / 1024.0 / 1024.0 / (elapsed / 1000.0)
        } else 0.0

        // 2. 写入 log 文件（记录测试信息，保留不删除）
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val logName = "test_$timestamp.log"
        val logContent = buildString {
            append("hello$timestamp\n")
            append("测试文件：$TEST_ZIP_NAME\n")
            append("文件大小：${formatBytes(zipSource.length())}\n")
            append("写入耗时：${elapsed}ms\n")
            append("写入速度：${String.format(Locale.US, "%.1f", speed)} MB/s\n")
        }
        val logUsb: UsbFile = testDir.createFile(logName)
        val logOs = UsbFileOutputStream(logUsb)
        try {
            logOs.write(logContent.toByteArray(Charsets.UTF_8))
        } finally {
            logOs.close()
        }

        // 3. 删除 U 盘上的 zip，log 保留
        runCatching { zipUsb.delete() }

        val detail = listOf(
            "$TEST_ZIP_NAME：${formatBytes(zipSource.length())}，耗时 ${elapsed}ms，" +
                "速度 ${String.format(Locale.US, "%.1f", speed)} MB/s",
            "已生成日志 $logName（保留）"
        )
        val speedText = String.format(Locale.US, "%.1f", speed) + " MB/s"
        val summary = "$TEST_ZIP_NAME 写入成功，速度 $speedText"
        return TestOutcome(summary, detail, speedText)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.1f KB", kb)
        }
    }

    /**
     * 从设备描述符的 bcdUSB 字段解析 USB 协议版本（2.0/3.0/3.2）。
     * 注意：UsbDevice.getVersion() 返回的是设备固件版本号（bcdDevice），并非 USB 协议版本，
     * 例如 KIOXIA 上报 "0.01"、SanDisk 上报 "1.00"，因此不能用于判断 2.0/3.0/3.2。
     */
    private fun queryUsbVersion(usbDevice: UsbDevice): String {
        val usbManager = getApplication<Application>()
            .getSystemService(Context.USB_SERVICE) as? UsbManager ?: return "未知"
        val connection = usbManager.openDevice(usbDevice) ?: return "未知"
        return try {
            val raw = connection.rawDescriptors
            if (raw == null || raw.size < 4) {
                "未知"
            } else {
                // 设备描述符：偏移 2-3 为 bcdUSB（小端）
                val bcdUsb = ((raw[3].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
                when (bcdUsb) {
                    0x0100 -> "USB 1.0"
                    0x0110 -> "USB 1.1"
                    0x0200 -> "USB 2.0"
                    0x0300 -> "USB 3.0"
                    0x0301 -> "USB 3.1"
                    0x0320 -> "USB 3.2"
                    else -> "USB ${bcdUsb shr 8}.${String.format(Locale.US, "%02x", bcdUsb and 0xFF)}"
                }
            }
        } finally {
            connection.close()
        }
    }

    private fun addLog(message: String) {
        appLogs.add(AppLog(now(), message))
        while (appLogs.size > MAX_LOGS) {
            appLogs.removeAt(0)
        }
    }

    private fun now(): String = timeFormat.format(Date())

    private companion object {
        const val TAG = "UsbTest"
        const val TEST_DIR_NAME = "test"
        const val TEST_FILE_DIR = "test_files"
        const val TEST_ZIP_NAME = "c38-firmware.zip"
        const val MAX_LOGS = 800
    }
}
