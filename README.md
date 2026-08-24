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
App (Compose UI)
 └─ UsbTestViewModel（设备管理 / 测试流程 / 统计）
     └─ libaums core 0.10.0（USB/SCSI 层、FAT32）
         └─ javafs wrapper（me.jahnen.libaums.javafs）
             └─ jnode 文件系统源码（org.jnode，LGPL）
                 └─ exFAT 写实现（自研扩展）
```

exFAT 写实现位于 `app/src/main/java/org/jnode/fs/exfat/`，自研补充了：
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
| me.jahnen.libaums:core:0.10.0 | USB Mass Storage / FAT32 |
| log4j / android-logging-log4j | jnode 日志 |
| jnode 源码（内嵌） | exFAT/NTFS 等文件系统 |
