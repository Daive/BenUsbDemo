package me.jahnen.libaums.javafs;

import android.util.Log;

import de.mindpipe.android.logging.log4j.LogCatAppender;
import me.jahnen.libaums.core.driver.BlockDeviceDriver;
import me.jahnen.libaums.core.fs.FileSystem;
import me.jahnen.libaums.core.fs.FileSystemCreator;
import me.jahnen.libaums.core.partition.PartitionTableEntry;
import me.jahnen.libaums.javafs.wrapper.device.DeviceWrapper;
import me.jahnen.libaums.javafs.wrapper.device.FSBlockDeviceWrapper;
import me.jahnen.libaums.javafs.wrapper.fs.FileSystemWrapper;

import org.apache.log4j.Logger;
import org.jnode.fs.FileSystemException;
import org.jnode.fs.FileSystemType;
import org.jnode.fs.exfat.ExFatFileSystemType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * FileSystemCreator based on java-fs (JNode) which provides exFAT support.
 * 注意：FAT32 等由 libaums core 原生支持，这里只注册 exFAT，
 * 避免 jnode 的 FAT（jfat）实现与 core 冲突。
 */
public class JavaFsFileSystemCreator implements FileSystemCreator {

    private static final String TAG = JavaFsFileSystemCreator.class.getSimpleName();

    private static final List<FileSystemType> FS_TYPES = new ArrayList<>();

    static {
        // 配置 log4j 输出到 logcat，避免 jnode 日志器无 appender 报错
        final Logger root = Logger.getRootLogger();
        root.addAppender(new LogCatAppender());

        FS_TYPES.add(new ExFatFileSystemType());
    }

    @Override
    public FileSystem read(PartitionTableEntry entry, BlockDeviceDriver blockDevice) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        blockDevice.read(0, buffer);

        FSBlockDeviceWrapper wrapper = new FSBlockDeviceWrapper(blockDevice, entry);

        for (FileSystemType type : FS_TYPES) {
            if (type.supports(wrapper.getPartitionTableEntry(), buffer.array(), wrapper)) {
                try {
                    return new FileSystemWrapper(type.create(new DeviceWrapper(blockDevice, entry), false));
                } catch (FileSystemException e) {
                    Log.e(TAG, "error creating fs with type " + type.getName(), e);
                }
            }
        }

        return null;
    }
}
