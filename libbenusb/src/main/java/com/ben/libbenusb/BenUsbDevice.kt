package com.ben.libbenusb

import android.hardware.usb.UsbDevice
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.partition.Partition
import me.jahnen.libaums.javafs.wrapper.fs.FileSystemWrapper
import java.io.IOException

/**
 * 表示一个已连接的 USB 大容量存储设备（封装 libaums 的 UsbMassStorageDevice）。
 */
class BenUsbDevice internal constructor(
    internal val massDevice: UsbMassStorageDevice,
) {

    /** 底层 UsbDevice，用于权限请求等 */
    val usbDevice: UsbDevice
        get() = massDevice.usbDevice

    /** 初始化设备（读取分区/文件系统），访问文件前必须调用 */
    @Throws(IOException::class)
    fun init() {
        massDevice.init()
    }

    /** 关闭并释放设备 */
    @Throws(IOException::class)
    fun close() {
        massDevice.close()
    }

    /** 设备的分区列表（init 后可用） */
    val partitions: List<Partition>
        get() = massDevice.partitions

    /** 第一个分区的文件系统（exFAT/FAT32 等），可能为 null */
    val fileSystem: FileSystem?
        get() = massDevice.partitions.firstOrNull()?.fileSystem

    /** 文件系统类型名称（如 exFAT/FAT32），无文件系统时为 null */
    val fileSystemTypeName: String?
        get() = fileSystem?.let { fs ->
            val simpleName = fs.javaClass.simpleName
            when {
                simpleName == "FileSystemWrapper" ->
                    (fs as? FileSystemWrapper)?.typeName?.ifBlank { "其他" }
                simpleName.contains("Fat32", ignoreCase = true) -> "FAT32"
                simpleName.contains("Fat16", ignoreCase = true) -> "FAT16"
                simpleName.contains("Fat12", ignoreCase = true) -> "FAT12"
                else -> simpleName
            }
        }
}
