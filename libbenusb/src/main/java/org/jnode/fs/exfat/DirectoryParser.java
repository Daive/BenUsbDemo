/*
 * $Id$
 *
 * Copyright (C) 2003-2015 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public 
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
 
package org.jnode.fs.exfat;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author Matthias Treydte &lt;waldheinz at gmail.com&gt;
 */
public class DirectoryParser {

    private static final String TAG = DirectoryParser.class.getSimpleName();

    private static final int ENTRY_SIZE = 32;
    private static final int ENAME_MAX_LEN = 15;
    private static final int VALID = 0x80;
    private static final int CONTINUED = 0x40;

    /**
     * If this bit is not set it means "critical", if it is set "benign".
     */
    private static final int IMPORTANCE_MASK = 0x20;

    private static final int EOD = (0x00);
    private static final int BITMAP = (0x01 | VALID);
    private static final int UPCASE = (0x02 | VALID);
    private static final int LABEL = (0x03 | VALID);
    private static final int FILE = (0x05);
    private static final int FILE_INFO = (0x00 | CONTINUED);
    private static final int FILE_NAME = (0x01 | CONTINUED);

    private static final int FLAG_FRAGMENTED = 1;
    private static final int FLAG_CONTIGUOUS = 3;

    public static DirectoryParser create(Node node) throws IOException {
        return create(node, false);
    }

    public static DirectoryParser create(Node node, boolean showDeleted) throws IOException {
        assert (node.isDirectory()) : "not a directory"; //NOI18N

        final DirectoryParser result = new DirectoryParser(node, showDeleted);
        result.init();
        return result;
    }

    private final ExFatSuperBlock sb;
    private final ByteBuffer chunk;
    private final Node node;
    private boolean showDeleted;
    private long cluster;
    private UpcaseTable upcase;
    private int index;
    private long dirClusters = 1;
    private long scannedClusters = 0;

    private DirectoryParser(Node node, boolean showDeleted) {
        this.node = node;
        this.showDeleted = showDeleted;
        this.sb = node.getSuperBlock();
        this.chunk = ByteBuffer.allocate(sb.getBytesPerCluster());
        this.chunk.order(ByteOrder.LITTLE_ENDIAN);
        this.cluster = node.getStartCluster();
        this.upcase = null;
    }

    public DirectoryParser setUpcase(UpcaseTable upcase) {
        if (this.upcase != null) {
            throw new IllegalStateException("already had an upcase table");
        }

        this.upcase = upcase;

        return this;
    }

    private void init() throws IOException {
        this.sb.readCluster(chunk, cluster);
        chunk.rewind();
    }

    private boolean advance() throws IOException {
        assert ((chunk.position() % ENTRY_SIZE) == 0) :
            "not on entry boundary"; //NOI18N

        if (chunk.remaining() == 0) {
            scannedClusters++;
            if (scannedClusters >= dirClusters) {
                return false;
            }

            cluster = node.nextCluster(cluster);

            if (Cluster.invalid(cluster)) {
                return false;
            }

            this.chunk.rewind();
            this.sb.readCluster(chunk, cluster);
            this.chunk.rewind();
        }

        return true;
    }

    private void skip(int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            if (chunk.remaining() == 0) {
                // 跨簇：切到下一个簇；簇链结束则钳制到末尾
                if (!advance()) {
                    chunk.position(chunk.limit());
                    return;
                }
            }
            final int step = Math.min(remaining, chunk.remaining());
            chunk.position(chunk.position() + step);
            remaining -= step;
        }
    }

    public void parse(Visitor v) throws IOException {
        final int bpc = sb.getBytesPerCluster();
        dirClusters = node.getClusterCount();
        if (dirClusters == 0) {
            final long dirSize = node.getSize();
            dirClusters = dirSize > 0 ? (dirSize + bpc - 1) / bpc : 1;
        }
        scannedClusters = 0;

        while (true) {
            final int entryType = DeviceAccess.getUint8(chunk);

            if (entryType == LABEL) {
                parseLabel(v);

            } else if (entryType == BITMAP) {
                parseBitmap(v);

            } else if (entryType == UPCASE) {
                parseUpcaseTable(v);

            } else if ((entryType & FILE) == FILE) {
                boolean deleted = (entryType & VALID) == 0;
                if (showDeleted || !deleted) {
                    parseFile(v, deleted);
                } else {
                    skip(ENTRY_SIZE - 1);
                }

            } else if (entryType == EOD) {
                // 0x00 既表示未使用条目也表示目录结束；删除的条目也在目录中间，
                // 因此这里跳过并继续扫描，由簇数上限保证终止
                skip(ENTRY_SIZE - 1);

            } else if (entryType == 0xC0 || entryType == 0xC1) {
                // 孤立的 stream/name 条目（删除残留），跳过而非报错
                Log.w(TAG, "skipping orphan entry type " + Integer.toHexString(entryType));
                skip(ENTRY_SIZE - 1);

            } else {
                // 未知条目一律跳过（宽容模式），兼容部分工具/系统生成的条目（如 0xC9）
                if ((entryType & VALID) != 0) {
                    Log.w(TAG, "skipping unknown entry type " + Integer.toHexString(entryType));
                }
                skip(ENTRY_SIZE - 1);
            }

            if (!advance()) {
                break;
            }

            index++;
        }
    }

    private void parseLabel(Visitor v) throws IOException {
        final int len = DeviceAccess.getUint8(chunk);

        if (len > ENAME_MAX_LEN) {
            // 宽容：异常的 label 长度，跳过该条目
            Log.w(TAG, "label length " + len + " too long, skipping");
            skip(ENTRY_SIZE - 2);
            return;
        }

        final StringBuilder labelBuilder = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            labelBuilder.append(DeviceAccess.getChar(chunk));
        }

        v.foundLabel(labelBuilder.toString());

        skip((ENAME_MAX_LEN - len) * DeviceAccess.BYTES_PER_CHAR);
    }

    private void parseBitmap(Visitor v) throws IOException {
        skip(19); /* unknown content */

        final long startCluster = DeviceAccess.getUint32(chunk);
        final long size = readUint64Safe(chunk);

        v.foundBitmap(startCluster, size);
    }

    private void parseUpcaseTable(Visitor v) throws IOException {
        skip(3); /* unknown */
        final long checksum = DeviceAccess.getUint32(chunk);
        assert (checksum >= 0);

        skip(12); /* unknown */
        final long startCluster = DeviceAccess.getUint32(chunk);
        final long size = readUint64Safe(chunk);

        v.foundUpcaseTable(this, startCluster, size, checksum);
    }

    /**
     * 读取 8 字节无符号长度字段；若最高位为 1（异常数据）则返回 0 而非抛异常，
     * 兼容部分设备/工具产生的异常目录条目。
     */
    private long readUint64Safe(ByteBuffer src) {
        try {
            return DeviceAccess.getUint64(src);
        } catch (IOException e) {
            Log.w(TAG, "failed to read uint64, using 0", e);
            return 0;
        }
    }

    private void parseFile(Visitor v, boolean deleted) throws IOException {
        int actualChecksum = startChecksum();

        int conts = DeviceAccess.getUint8(chunk);

        if (conts < 2) {
            // 宽容：损坏的 file entry（次级条目数 < 2），跳过当前及后续条目
            Log.w(TAG, "too few continuations (" + conts + "), skipping entry");
            skip(ENTRY_SIZE - 1);
            while (conts-- > 0) {
                advance();
                skip(ENTRY_SIZE - 1);
            }
            return;
        }

        final int referenceChecksum = DeviceAccess.getUint16(chunk);
        final int attrib = DeviceAccess.getUint16(chunk);
        skip(2); /* unknown */
        final EntryTimes times = EntryTimes.read(chunk);
        skip(7); /* unknown */

        advance();

        actualChecksum = addChecksum(actualChecksum);

        if ((DeviceAccess.getUint8(chunk) & FILE_INFO) != FILE_INFO) {
            // 宽容：file entry 后的次级条目不是 stream（异常数据），跳过整个条目组
            Log.w(TAG, "expected file info, skipping entry group");
            skip(ENTRY_SIZE - 1);
            int remaining = conts - 1;
            while (remaining-- > 0) {
                advance();
                skip(ENTRY_SIZE - 1);
            }
            return;
        }

        if (deleted) {
            // Keep the index consistent with the index when not recovering deleted files
            index++;
        }

        final int flag = DeviceAccess.getUint8(chunk);
        skip(1); /* unknown */
        int nameLen = DeviceAccess.getUint8(chunk);
        final int nameHash = DeviceAccess.getUint16(chunk);
        skip(2); /* unknown */
        final long realSize = readUint64Safe(chunk);
        skip(4); /* unknown */
        final long startCluster = DeviceAccess.getUint32(chunk);
        final long size = readUint64Safe(chunk);

        if (realSize != size) {
            // exFAT 允许 valid data length 与 data length 不一致（如写入中途或部分分配），放宽为警告
            Log.w(TAG, "real size does not equal size: " + realSize + " != " + size);
        }

        conts--;

        /* read file name */
        final StringBuilder nameBuilder = new StringBuilder(nameLen);

        while (conts-- > 0) {
            advance();
            actualChecksum = addChecksum(actualChecksum);

            if ((DeviceAccess.getUint8(chunk) & FILE_NAME) != FILE_NAME) {
                // 宽容：非 name 条目（异常数据），跳过当前及剩余条目，使用已读取的文件名
                Log.w(TAG, "expected file name, skipping " + (conts + 1) + " entries");
                skip(ENTRY_SIZE - 1);
                while (conts-- > 0) {
                    advance();
                    skip(ENTRY_SIZE - 1);
                }
                break;
            }

            if (deleted) {
                // Keep the index consistent with the index when not recovering deleted files
                index++;
            }

            skip(1); /* unknown */

            final int toRead = Math.min(ENAME_MAX_LEN, nameLen);

            for (int i = 0; i < toRead; i++) {
                nameBuilder.append(DeviceAccess.getChar(chunk));
            }

            nameLen -= toRead;
            assert (nameLen >= 0);

            if (nameLen == 0) {
                assert (conts == 0) : "conts remaining?!"; //NOI18N
                skip((ENAME_MAX_LEN - toRead) * DeviceAccess.BYTES_PER_CHAR);
            }
        }

        if (!deleted && referenceChecksum != actualChecksum) {
            // 宽容：校验和不匹配仅表示数据异常，不阻塞挂载
            Log.w(TAG, "checksum mismatch for file entry");
        }

        final String name = nameBuilder.toString();

        if ((this.upcase != null) && (hashName(name) != nameHash)) {
            // 名称哈希不匹配仅影响按名查找的优化，放宽为警告以兼容部分设备（如 Kingston DataTraveler）
            Log.w(TAG, "name hash mismatch (computed="
                + Integer.toHexString(hashName(name)) +
                " != stored=" + Integer.toHexString(nameHash) + ") for name: " + name);
        }

        v.foundNode(Node.create(sb, startCluster, attrib, name, (flag == FLAG_CONTIGUOUS), realSize, times, deleted),
            index);
    }

    private int hashName(String name) throws IOException {
        int hash = 0;

        for (int i = 0; i < name.length(); i++) {
            final int c = this.upcase.toUpperCase(name.charAt(i));

            hash = ((hash << 15) | (hash >> 1)) + (c & 0xff);
            hash &= 0xffff;
            hash = ((hash << 15) | (hash >> 1)) + (c >> 8);
            hash &= 0xffff;
        }

        return (hash & 0xffff);
    }

    private int startChecksum() {
        final int oldPos = chunk.position();
        chunk.position(chunk.position() - 1);
        assert ((chunk.position() % ENTRY_SIZE) == 0);

        int result = 0;

        for (int i = 0; i < ENTRY_SIZE; i++) {
            final int b = DeviceAccess.getUint8(chunk);
            if ((i == 2) || (i == 3)) continue;
            result = ((result << 15) | (result >> 1)) + b;
            result &= 0xffff;
        }

        chunk.position(oldPos);
        return result;
    }

    private int addChecksum(int sum) {
        chunk.mark();
        assert ((chunk.position() % ENTRY_SIZE) == 0);

        for (int i = 0; i < ENTRY_SIZE; i++) {
            sum = ((sum << 15) | (sum >> 1)) + DeviceAccess.getUint8(chunk);
            sum &= 0xffff;
        }

        chunk.reset();
        return sum;
    }

    interface Visitor {

        public void foundLabel(
                String label) throws IOException;

        /**
         * @param startCluster
         * @param size         bitmap size in bytes
         */
        public void foundBitmap(
                long startCluster, long size) throws IOException;

        /**
         * @param checksum
         * @param startCluster
         * @param size         table size in bytes
         */
        public void foundUpcaseTable(DirectoryParser parser,
                                     long checksum, long startCluster, long size) throws IOException;

        public void foundNode(Node node, int index) throws IOException;
    }

}
