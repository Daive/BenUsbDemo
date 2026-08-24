package com.ben.usbdemo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ben.libbenusb.BenUsb
import com.ben.libbenusb.BenUsbDevice
import com.ben.usbdemo.ui.theme.UsbDemoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: UsbTestViewModel by viewModels()

    private val usbManager: UsbManager by lazy {
        getSystemService(UsbManager::class.java)
    }

    private val permissionIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDeviceExtra()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        findMassDevice(device)?.let { viewModel.addDevice(it) }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    intent.usbDeviceExtra()?.let { viewModel.removeDevice(it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ 动态注册广播必须指定 exported 标志（系统 USB 广播需 EXPORTED）
        registerReceiver(
            usbReceiver,
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            Context.RECEIVER_EXPORTED
        )

        handleAttachedIntent(intent)

        // APP 打开后延迟 200ms 检查是否已有插入的 U 盘
        lifecycleScope.launch {
            delay(200)
            refreshDevices()
        }

        setContent {
            UsbDemoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UsbTestScreen(viewModel, Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAttachedIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun handleAttachedIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.usbDeviceExtra()?.let { handleAttachedDevice(it) }
        }
    }

    private val pendingPermission = HashSet<Int>()

    private fun refreshDevices() {
        for (device in BenUsb.getMassStorageDevices(this)) {
            val usbDevice = device.usbDevice
            if (usbManager.hasPermission(usbDevice)) {
                viewModel.addDevice(device)
            } else if (pendingPermission.add(usbDevice.deviceId)) {
                usbManager.requestPermission(usbDevice, permissionIntent)
            }
        }
    }

    private fun handleAttachedDevice(usbDevice: UsbDevice) {
        val massDevice = findMassDevice(usbDevice) ?: return
        if (usbManager.hasPermission(usbDevice)) {
            viewModel.addDevice(massDevice)
        } else if (pendingPermission.add(usbDevice.deviceId)) {
            usbManager.requestPermission(usbDevice, permissionIntent)
        }
    }

    private fun findMassDevice(usbDevice: UsbDevice): BenUsbDevice? {
        return BenUsb.getMassStorageDevices(this)
            .firstOrNull { it.usbDevice.deviceId == usbDevice.deviceId }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private companion object {
        const val ACTION_USB_PERMISSION = "com.ben.usbdemo.USB_PERMISSION"
    }
}

@Composable
fun UsbTestScreen(viewModel: UsbTestViewModel, modifier: Modifier = Modifier) {
    val devices = viewModel.devices
    val appLogs = viewModel.appLogs
    val testRecords = viewModel.testRecords
    var selectedId by remember { mutableStateOf<Int?>(null) }

    Row(modifier = modifier.fillMaxSize()) {
        // 第一列：U 盘列表（1/4 宽）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val context = LocalContext.current
            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "?"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "U 盘列表",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "v$versionName",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (devices.isEmpty()) {
                Text(
                    "未检测到 U 盘",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(devices, key = { it.usbDevice.deviceId }) { item ->
                        UsbDeviceRow(
                            item = item,
                            selected = item.usbDevice.deviceId == selectedId,
                            onClick = {
                                selectedId = item.usbDevice.deviceId
                                viewModel.testDevice(item)
                            }
                        )
                    }
                }
            }
        }

        // 第二列：APP 操作日志（2/4 宽）
        Column(
            modifier = Modifier
                .weight(2f)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("APP 操作日志", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (appLogs.isEmpty()) {
                Text(
                    "暂无日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(appLogs.asReversed()) { log -> AppLogRow(log) }
                }
            }
        }

        // 第三列：测试统计记录（1/4 宽）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val successCount = testRecords.count { it.result.startsWith("成功") }
            Text(
                "测试记录（共 ${testRecords.size} 次 · 成功 $successCount 次）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp)
            )
            if (testRecords.isEmpty()) {
                Text(
                    "暂无测试记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(testRecords.asReversed()) { record -> TestRecordRow(record) }
                }
            }
        }
    }
}

@Composable
private fun TestRecordRow(record: TestRecord) {
    val resultColor =
        if (record.result.startsWith("成功")) Color(0xFF4CAF50) else Color(0xFFF44336)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                RoundedCornerShape(6.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            record.time,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${record.deviceName} · ${record.fileSystemType} · ${record.usbVersion}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("速度：${record.speedText}", fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                record.result,
                fontSize = 12.sp,
                color = resultColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppLogRow(log: AppLog) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            log.time,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(8.dp))
        Text(
            log.message,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun UsbDeviceRow(item: UsbDeviceItem, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .padding(12.dp)
    ) {
        Text(
            item.name,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${item.manufacturer} · ${item.fileSystemType} · ${item.capacityText} · ${item.usbVersionText}",
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(item.status)
            Spacer(Modifier.width(8.dp))
            Text(
                item.message,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBadge(status: UsbTestStatus) {
    val (text, color) = when (status) {
        UsbTestStatus.INIT -> "初始化" to Color(0xFF9E9E9E)
        UsbTestStatus.TESTING -> "测试中" to Color(0xFFFF9800)
        UsbTestStatus.SUCCESS -> "测试成功" to Color(0xFF4CAF50)
        UsbTestStatus.FAILED -> "测试失败" to Color(0xFFF44336)
    }
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
