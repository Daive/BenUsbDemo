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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import org.jnode.fs.FSDirectory;
import org.jnode.fs.FSDirectoryId;
import org.jnode.fs.FSEntry;
import org.jnode.fs.spi.AbstractFSObject;

/**
 * @author Matthias Treydte &lt;waldheinz at gmail.com&gt;
 */
public class NodeDirectory extends AbstractFSObject implements FSDirectory, FSDirectoryId {

    private static final int ENTRY_SIZE = 32;

    private final NodeEntry nodeEntry;
    private final Map<String, NodeEntry> nameToNode;
    private final Map<String, NodeEntry> idToNode;
    private final UpcaseTable upcase;

    public NodeDirectory(ExFatFileSystem fs, NodeEntry nodeEntry)
        throws IOException {

        this(fs, nodeEntry, false);
    }

    public NodeDirectory(ExFatFileSystem fs, NodeEntry nodeEntry, boolean showDeleted)
        throws IOException {

        super(fs);

        this.nodeEntry = nodeEntry;
        this.upcase = fs.getUpcase();
        this.nameToNode = new LinkedHashMap<String, NodeEntry>();
        this.idToNode = new LinkedHashMap<String, NodeEntry>();

        DirectoryParser.
            create(nodeEntry.getNode(), showDeleted).
            setUpcase(this.upcase).
            parse(new VisitorImpl());

    }

    @Override
    public String getDirectoryId() {
        return Long.toString(nodeEntry.getNode().getStartCluster());
    }

    @Override
    public Iterator<FSEntry> iterator() {
        return Collections.<FSEntry>unmodifiableCollection(
            idToNode.values()).iterator();
    }

    @Override
    public FSEntry getEntry(String name) throws IOException {
        return this.nameToNode.get(upcase.toUpperCase(name));
    }

    @Override
    public FSEntry getEntryById(String id) throws IOException {
        NodeEntry nodeEntry = idToNode.get(id);

        if (nodeEntry != null) {
            return nodeEntry;
        }

        throw new IOException("Failed to find entry with ID:" + id);
    }

    @Override
    public FSEntry addFile(String name) throws IOException {
        return addEntry(name, Node.ATTRIB_ARCH, 0);
    }

    @Override
    public FSEntry addDirectory(String name) throws IOException {
        ExFatFileSystem fs = (ExFatFileSystem) getFileSystem();
        ClusterBitMap bitmap = fs.getClusterBitmap();

        final long cluster = bitmap.allocate();

        // 初始化新目录簇：全部填 0（表示目录结束/空闲条目）
        final ByteBuffer zero = ByteBuffer.allocate(sb().getBytesPerCluster());
        zero.order(ByteOrder.LITTLE_ENDIAN);
        sb().writeCluster(zero, cluster);

        final NodeEntry entry = (NodeEntry) addEntry(name, Node.ATTRIB_DIR, cluster);
        entry.getNode().setClusterCount(1);
        return entry;
    }

    @Override
    public void remove(String name) throws IOException {
        final NodeEntry entry = nameToNode.remove(upcase.toUpperCase(name));
        if (entry == null) {
            throw new IOException("entry not found: " + name);
        }
        idToNode.remove(entry.getId());

        final Node node = entry.getNode();

        // 释放数据簇
        if (node.getStartCluster() != 0 && node.getClusterCount() > 0) {
            final ClusterBitMap bitmap = ((ExFatFileSystem) getFileSystem()).getClusterBitmap();
            for (long i = 0; i < node.getClusterCount(); i++) {
                bitmap.setClusterUsed(node.getStartCluster() + i, false);
            }
        }

        // 将该条目组的所有条目（file + stream + name）置为 0x00 标记未使用，
        // 避免残留的 stream/name 条目在重新挂载时被当作孤立条目
        final long index = node.getEntryIndex();
        if (index >= 0) {
            final int nameEntries = (node.getName().length() + 14) / 15;
            final int total = 2 + nameEntries;
            writeEntries(index, new byte[total * ENTRY_SIZE]);
        }
    }

    @Override
    public void flush() throws IOException {
        /* nothing to do */
    }

    /**
     * 将节点当前的起始簇/长度写回目录条目（stream entry），供 flush 后持久化。
     *
     * @param node the node whose metadata should be written back
     * @throws IOException on write error
     */
    public void writeBack(Node node) throws IOException {
        final long index = node.getEntryIndex();
        if (index < 0) {
            return;
        }

        final int nameLen = node.getName().length();
        final int nameEntries = (nameLen + 14) / 15;
        final int total = 2 + nameEntries;

        final byte[] entries = readEntries(index, total);

        final int streamOff = 32;
        entries[streamOff + 1] = (byte) 3;              // contiguous, no FAT chain
        writeUint32(entries, streamOff + 4, nameLen);
        writeUint64(entries, streamOff + 12, node.getSize());  // valid data length
        writeUint32(entries, streamOff + 20, (int) node.getStartCluster());
        writeUint64(entries, streamOff + 24, node.getSize());  // data length

        final int checksum = computeChecksum(entries);
        entries[2] = (byte) (checksum & 0xff);
        entries[3] = (byte) ((checksum >> 8) & 0xff);

        writeEntries(index, entries);
    }

    /**
     * Gets the node associated with this directory.
     *
     * @return the node.
     */
    public Node getNode() {
        return nodeEntry.getNode();
    }

    /**
     * Gets the parent directory.
     *
     * @return the parent directory, or {@code null} if this is the root directory.
     */
    public FSDirectory getParent() {
        return nodeEntry.getParent();
    }

    private ExFatSuperBlock sb() {
        return getNode().getSuperBlock();
    }

    private FSEntry addEntry(String name, int attrib, long firstCluster) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("empty name");
        }

        final int nameLen = name.length();
        final int nameEntries = (nameLen + 14) / 15;
        final int secondary = 1 + nameEntries;
        final int total = 1 + secondary;

        final long freeIndex = findFreeEntry(total);

        final byte[] entries = new byte[total * ENTRY_SIZE];

        // file entry (0x85)
        entries[0] = (byte) 0x85;
        entries[1] = (byte) secondary;
        writeUint16(entries, 4, attrib);
        writeTimes(entries, 8);

        // stream extension entry (0xC0)
        final int streamOff = 32;
        entries[streamOff] = (byte) 0xC0;
        entries[streamOff + 1] = (byte) 3;             // contiguous, no FAT chain
        writeUint32(entries, streamOff + 4, nameLen);
        writeUint16(entries, streamOff + 8, computeNameHash(name));
        writeUint64(entries, streamOff + 12, 0);       // valid data length
        writeUint32(entries, streamOff + 20, (int) firstCluster);
        writeUint64(entries, streamOff + 24, 0);       // data length

        // file name entries (0xC1), 15 UTF-16 chars each
        final int nameOff = streamOff + 32;
        for (int i = 0; i < nameEntries; i++) {
            final int off = nameOff + i * 32;
            entries[off] = (byte) 0xC1;
            for (int j = 0; j < 15; j++) {
                final int charIndex = i * 15 + j;
                writeUint16(entries, off + 2 + j * 2,
                    charIndex < nameLen ? name.charAt(charIndex) : 0);
            }
        }

        // set checksum (bytes 2-3 of the file entry)
        final int checksum = computeChecksum(entries);
        entries[2] = (byte) (checksum & 0xff);
        entries[3] = (byte) ((checksum >> 8) & 0xff);

        writeEntries(freeIndex, entries);

        final Node node = Node.create(sb(), firstCluster, attrib, name, true, 0,
            new EntryTimes(new Date(), new Date(), new Date()), false);
        node.setEntryLocation(this, freeIndex);
        final NodeEntry entry = new NodeEntry((ExFatFileSystem) getFileSystem(), node, this, (int) freeIndex);
        nameToNode.put(upcase.toUpperCase(name), entry);
        idToNode.put(entry.getId(), entry);
        return entry;
    }

    /**
     * 在目录中寻找连续的空闲（0x00）条目槽位。
     */
    private long findFreeEntry(int total) throws IOException {
        final int bpc = sb().getBytesPerCluster();
        final int entriesPerCluster = bpc / ENTRY_SIZE;

        long clusterCount = getNode().getClusterCount();
        if (clusterCount == 0) {
            final long dirSize = getNode().getSize();
            clusterCount = dirSize > 0 ? (dirSize + bpc - 1) / bpc : 1;
        }

        long cluster = getNode().getStartCluster();
        int index = 0;
        int run = 0;
        long runStart = -1;

        for (long c = 0; c < clusterCount; c++) {
            final ByteBuffer chunk = ByteBuffer.allocate(bpc);
            chunk.order(ByteOrder.LITTLE_ENDIAN);
            sb().readCluster(chunk, cluster);
            chunk.rewind();

            for (int i = 0; i < entriesPerCluster; i++) {
                final int type = chunk.get(i * ENTRY_SIZE) & 0xff;
                if (type == 0x00) {
                    if (run == 0) {
                        runStart = index;
                    }
                    run++;
                    if (run >= total) {
                        return runStart;
                    }
                } else {
                    run = 0;
                }
                index++;
            }

            cluster = getNode().nextCluster(cluster);
        }

        throw new IOException("directory full, cannot add entry");
    }

    /**
     * 读取位于 index 槽位的 total 个连续目录条目（跨簇）。
     */
    private byte[] readEntries(long index, int total) throws IOException {
        final byte[] result = new byte[total * ENTRY_SIZE];
        final int bpc = sb().getBytesPerCluster();
        final int entriesPerCluster = bpc / ENTRY_SIZE;

        long clusterIndex = index / entriesPerCluster;
        int inClusterIndex = (int) (index % entriesPerCluster);
        long cluster = getClusterAt(clusterIndex);

        int read = 0;
        while (read < result.length) {
            final ByteBuffer buf = ByteBuffer.allocate(bpc);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            sb().readCluster(buf, cluster);
            buf.rewind();

            final int chunk = Math.min((entriesPerCluster - inClusterIndex) * ENTRY_SIZE,
                result.length - read);
            buf.position(inClusterIndex * ENTRY_SIZE);
            buf.get(result, read, chunk);

            read += chunk;
            inClusterIndex = 0;
            cluster = getClusterAt(++clusterIndex);
        }

        return result;
    }

    /**
     * 将目录条目数据写入 index 槽位（跨簇，先读后写）。
     */
    private void writeEntries(long index, byte[] data) throws IOException {
        final int bpc = sb().getBytesPerCluster();
        final int entriesPerCluster = bpc / ENTRY_SIZE;

        long clusterIndex = index / entriesPerCluster;
        int inClusterIndex = (int) (index % entriesPerCluster);
        long cluster = getClusterAt(clusterIndex);

        int written = 0;
        while (written < data.length) {
            final ByteBuffer buf = ByteBuffer.allocate(bpc);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            sb().readCluster(buf, cluster);
            buf.rewind();

            final int chunk = Math.min((entriesPerCluster - inClusterIndex) * ENTRY_SIZE,
                data.length - written);
            buf.position(inClusterIndex * ENTRY_SIZE);
            buf.put(data, written, chunk);

            buf.rewind();
            sb().writeCluster(buf, cluster);

            written += chunk;
            inClusterIndex = 0;
            cluster = getClusterAt(++clusterIndex);
        }
    }

    /**
     * 获取目录中第 clusterIndex 个簇的簇号。
     */
    private long getClusterAt(long clusterIndex) throws IOException {
        long cluster = getNode().getStartCluster();
        for (long i = 0; i < clusterIndex; i++) {
            cluster = getNode().nextCluster(cluster);
        }
        return cluster;
    }

    /**
     * 计算文件名的 exFAT 名称哈希（与 DirectoryParser.hashName 算法一致）。
     */
    private int computeNameHash(String name) throws IOException {
        int hash = 0;
        for (int i = 0; i < name.length(); i++) {
            final int c = upcase.toUpperCase(name.charAt(i));
            hash = ((hash << 15) | (hash >> 1)) + (c & 0xff);
            hash &= 0xffff;
            hash = ((hash << 15) | (hash >> 1)) + (c >> 8);
            hash &= 0xffff;
        }
        return hash & 0xffff;
    }

    /**
     * 计算条目组的校验和（跳过 set checksum 字段，即条目组的字节 2、3）。
     */
    private static int computeChecksum(byte[] entries) {
        int sum = 0;
        for (int i = 0; i < entries.length; i++) {
            if (i == 2 || i == 3) {
                continue;
            }
            sum = ((sum << 15) | (sum >>> 1)) + (entries[i] & 0xff);
            sum &= 0xffff;
        }
        return sum;
    }

    private static void writeUint16(byte[] arr, int off, int v) {
        arr[off] = (byte) (v & 0xff);
        arr[off + 1] = (byte) ((v >> 8) & 0xff);
    }

    private static void writeUint32(byte[] arr, int off, long v) {
        arr[off] = (byte) (v & 0xff);
        arr[off + 1] = (byte) ((v >> 8) & 0xff);
        arr[off + 2] = (byte) ((v >> 16) & 0xff);
        arr[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static void writeUint64(byte[] arr, int off, long v) {
        for (int i = 0; i < 8; i++) {
            arr[off + i] = (byte) ((v >> (8 * i)) & 0xff);
        }
    }

    /**
     * 把当前时间写入 file entry 的时间戳字段（offset 8 起，17 字节）。
     */
    private static void writeTimes(byte[] arr, int off) {
        final Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(System.currentTimeMillis());

        final int year = cal.get(Calendar.YEAR) - 1980;
        final int month = cal.get(Calendar.MONTH) + 1;
        final int day = cal.get(Calendar.DAY_OF_MONTH);
        final int hour = cal.get(Calendar.HOUR_OF_DAY);
        final int minute = cal.get(Calendar.MINUTE);
        final int sec = cal.get(Calendar.SECOND);

        final int date = (year << 9) | (month << 5) | day;
        final int time = (hour << 11) | (minute << 5) | (sec / 2);

        writeUint16(arr, off, time);        // create time
        writeUint16(arr, off + 2, date);    // create date
        writeUint16(arr, off + 4, time);    // modified time
        writeUint16(arr, off + 6, date);    // modified date
        writeUint16(arr, off + 8, time);    // accessed time
        writeUint16(arr, off + 10, date);   // accessed date
        // 剩余 centiseconds / timezone 偏移字段保持 0
    }

    private class VisitorImpl implements DirectoryParser.Visitor {

        @Override
        public void foundLabel(String label) {
            /* ignore */
        }

        @Override
        public void foundBitmap(
            long startCluster, long size) {

            /* ignore */
        }

        @Override
        public void foundUpcaseTable(DirectoryParser parser, long checksum,
                                     long startCluster, long size) {
            
            /* ignore */
        }

        @Override
        public void foundNode(Node node, int index) throws IOException {
            final String upcaseName = upcase.toUpperCase(node.getName());

            NodeEntry nodeEntry = new NodeEntry((ExFatFileSystem) getFileSystem(), node, NodeDirectory.this, index);
            nameToNode.put(upcaseName, nodeEntry);
            idToNode.put(nodeEntry.getId(), nodeEntry);
        }

    }

}
