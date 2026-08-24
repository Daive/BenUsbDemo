package com.ben.libbenusb

import android.content.Context
import android.hardware.usb.UsbDevice
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystemFactory
import me.jahnen.libaums.javafs.JavaFsFileSystemCreator

/**
 * USB 大容量存储读取库入口，API 风格类似 libaums。
 *
 * 内部集成：
 * - libaums core（USB/SCSI 层、FAT32）
 * - jnode + javafs（exFAT 写入支持）
 */
object BenUsb {

    init {
        // 注册 javafs 文件系统创建器（exFAT 支持）；FAT32 由 libaums core 原生处理
        FileSystemFactory.registerFileSystem(JavaFsFileSystemCreator())
    }

    /** 黑名单：排除设备内置的 MSC 控制器（MicroTech_MSC，STMicroelectronics 0x0483:0x572A） */
    private val blacklist = setOf(
        1155 to 22314,
    )

    private fun isBlacklisted(usbDevice: UsbDevice): Boolean {
        return (usbDevice.vendorId to usbDevice.productId) in blacklist
    }

    /**
     * 枚举当前连接的 USB 大容量存储设备（已过滤黑名单）。
     *
     * @param context Context，用于获取 UsbManager
     * @return 大容量存储设备列表；无设备时返回空列表
     */
    fun getMassStorageDevices(context: Context): List<BenUsbDevice> {
        return UsbMassStorageDevice.getMassStorageDevices(context)
            .filter { !isBlacklisted(it.usbDevice) }
            .map { BenUsbDevice(it) }
    }
}
