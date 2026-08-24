# UsbDemo

Android USB U 盘读写测试应用。基于 libaums（USB Mass Storage）+ jnode 文件系统，支持 **exFAT / FAT32** U 盘的挂载、写入、读取与速度测试。

## 功能特性

- **U 盘热插拔监听**：插入自动检测、授权、挂载，拔出自动移除
- **文件系统支持**：
  - FAT32 —— libaums core 原生支持
  - exFAT —— 基于 jnode 自研写入实现（官方仅支持读取）
- **自动测试**：插入 U 盘 100ms 后自动向 `test/` 目录写入 `c38-firmware.zip`（121MB），计算写入速度，生成日志，测后删除压缩包
- **三列界面（1:2:1）**：
  - U 盘列表：设备名、品牌、格式、容量、USB 类型、状态
  - APP 操作日志：实时操作记录（时间戳 + 事件）
  - 测试统计：全部测试记录（名称、格式、USB 类型、写入速度、结果）+ 成功数统计
- **黑名单**：排除设备内置 MSC 控制器（如 MicroTech_MSC）

## 技术架构

```
app（业务逻辑 + UI）
 ├─ MainActivity（USB 权限/监听、Compose 三列界面）
 └─ UsbTestViewModel（设备管理 / 测试流程 / 统计 / 日志）
     └─ libbenusb（USB 读取库，com.ben.libbenusb）
         ├─ BenUsb / BenUsbDevice（设备枚举/挂载/文件系统接口）
         ├─ libaums core（USB/SCSI 层、FAT32）
         └─ jnode 文件系统源码（org.jnode，LGPL）
             └─ exFAT 写实现（自研扩展）
```

### libbenusb 对外接口（libaums 风格）

| 接口 | 说明 |
|------|------|
| `BenUsb.getMassStorageDevices(context)` | 枚举大容量存储设备（含黑名单过滤、exFAT 自动注册） |
| `BenUsbDevice` | `usbDevice` / `init()` / `close()` / `partitions` / `fileSystem` / `fileSystemTypeName` |
| `UsbFile` / `FileSystem` / 流 | typealias 重新导出，可直接读写文件 |

exFAT 写实现位于 `libbenusb/src/main/java/org/jnode/fs/exfat/`，自研补充了：
- `NodeDirectory`：目录条目创建（file/stream/name + 校验和 + 名称哈希）、删除、元数据写回
- `NodeFile`：长度扩展、连续簇批量写入（性能优化）、flush 持久化
- `ClusterBitMap`：批量 bitmap 分配（预分配 121MB 仅需 22ms）
- `DirectoryParser`：宽容解析（兼容异常条目设备）

## 构建打包

```powershell
$env:ANDROID_HOME='C:\Android\Sdk'
$env:ANDROID_SDK_ROOT='C:\Android\Sdk'
.\gradlew.bat :app:assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

release 构建使用 debug 签名（demo 用途）。

## 使用说明

1. 安装 APK 到设备，打开应用
2. 插入 U 盘（exFAT 或 FAT32），授权 USB 权限
3. 应用自动挂载并测试：`test/` 目录写入 `c38-firmware.zip` → 测速 → 写日志 → 删 zip
4. 左侧列表点击设备可重新测试；右侧查看实时日志；第三列查看历史测试统计

## libbenusb 使用指南

### 1. 集成依赖

`app/build.gradle.kts`：

```kotlin
dependencies {
    implementation(project(":libbenusb"))
}
```

AndroidManifest 声明 USB host 能力（用于接收热插拔）：

```xml
<uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

### 2. 基本流程

```
枚举设备 BenUsb.getMassStorageDevices() → 请求权限 → 挂载 device.init()
→ 获取文件系统 device.fileSystem → 文件读写 → device.close()
```

### 3. 完整示例

```kotlin
import com.ben.libbenusb.BenUsb
import com.ben.libbenusb.BenUsbDevice
import com.ben.libbenusb.UsbFile
import com.ben.libbenusb.UsbFileInputStream
import com.ben.libbenusb.UsbFileOutputStream

// 1. 枚举已连接的 U 盘（内部已过滤黑名单、自动注册 exFAT 支持）
val devices = BenUsb.getMassStorageDevices(context)

// 2. 对每个设备请求 USB 权限（系统弹窗）
devices.forEach { device ->
    usbManager.requestPermission(device.usbDevice, permissionIntent)
}
// permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), FLAG_IMMUTABLE)
// 授权后（ACTION_USB_PERMISSION 广播，EXTRA_PERMISSION_GRANTED=true）：

// 3. 挂载设备
val device: BenUsbDevice = ...
device.init()

// 4. 获取文件系统与根目录
val fs = device.fileSystem ?: return  // null 表示无可用文件系统
val root = fs.rootDirectory
val files = root.listFiles()
files.forEach { f -> println("${f.name} ${if (f.isDirectory) "[目录]" else f.length}") }

// 5. 目录/文件操作
val testDir = root.listFiles().firstOrNull { it.isDirectory && it.name == "test" }
    ?: root.createDirectory("test")
val file: UsbFile = testDir.createFile("hello.txt")

// 写入
UsbFileOutputStream(file).use { os ->
    os.write("hello usb".toByteArray(Charsets.UTF_8))
}

// 读取
val content = UsbFileInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) }

// 删除
file.delete()

// 6. 设备信息
println(device.fileSystemTypeName)   // "exFAT" / "FAT32" / null
println(fs.capacity)                 // 容量（字节）
println(fs.volumeLabel)              // 卷标

// 7. 完成/拔出时关闭
device.close()
```

### 4. 接口速查表

| 接口 | 说明 |
|------|------|
| `BenUsb.getMassStorageDevices(context)` | 枚举大容量存储设备；过滤黑名单；首次调用自动注册 exFAT 文件系统 |
| `BenUsbDevice.usbDevice` | 底层 `android.hardware.usb.UsbDevice`，用于权限请求 |
| `BenUsbDevice.init()` | 初始化设备（读取分区/文件系统），**访问文件前必须调用** |
| `BenUsbDevice.close()` | 关闭并释放设备 |
| `BenUsbDevice.partitions` | 分区列表（`List<Partition>`，init 后可用） |
| `BenUsbDevice.fileSystem` | 第一个分区的文件系统（`FileSystem?`，可能为 null） |
| `BenUsbDevice.fileSystemTypeName` | 文件系统类型名（"exFAT"/"FAT32"/…，`String?`） |
| `FileSystem.rootDirectory` | 根目录 `UsbFile` |
| `FileSystem.capacity / freeSpace / volumeLabel` | 容量、剩余空间、卷标 |
| `UsbFile.listFiles() / isDirectory / name / length` | 目录/文件查询 |
| `UsbFile.createDirectory(name) / createFile(name)` | 创建目录/文件 |
| `UsbFile.delete() / setName(name)` | 删除 / 重命名 |
| `UsbFileInputStream` / `UsbFileOutputStream` | 文件读写流（标准 `InputStream/OutputStream`） |

> 以上类型（`UsbFile`/`FileSystem`/流）由 libbenusb 通过 typealias 重新导出，使用方只需依赖 libbenusb，无需直接接触 libaums。

## 测试文件

- `c38-firmware.zip`（121MB）打包在 `app/src/main/assets/test_files/`
- 首次启动自动复制到应用内部存储（`files/test_files/`），后续直接使用
- 替换测试文件：将新文件放入 assets 目录并重新打包，或替换内部存储中的文件

## 注意事项

- 测试文件 121MB 超 GitHub 100MB 限制，使用 **Git LFS** 管理（克隆需安装 git-lfs）
- 系统时间错误会影响日志时间戳，需保证设备时间正确
- 部分 U 盘（如 Kingston DataTraveler）存在异常目录条目，解析器已做宽容处理
- 代理环境推送代码需配置 git 代理

## 依赖

| 依赖 | 用途 |
|------|------|
| libbenusb（本地模块） | USB 读取库（libaums + jnode exFAT） |
| me.jahnen.libaums:core:0.10.0 | USB Mass Storage / FAT32（libbenusb 内部） |
| log4j / android-logging-log4j | jnode 日志（libbenusb 内部） |
| jnode 源码（内嵌） | exFAT/NTFS 等文件系统（libbenusb 内部） |
