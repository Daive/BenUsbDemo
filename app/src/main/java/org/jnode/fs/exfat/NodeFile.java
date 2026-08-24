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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jnode.fs.FSFile;
import org.jnode.fs.spi.AbstractFSObject;

/**
 * @author Matthias Treydte &lt;waldheinz at gmail.com&gt;
 */
public class NodeFile extends AbstractFSObject implements FSFile {

    /** 批量写入时一次合并的最大簇数（32 簇 × 128KB ≈ 4MB） */
    private static final int MAX_BATCH_CLUSTERS = 32;

    private final Node node;

    public NodeFile(ExFatFileSystem fs, Node node) {
        super(fs);

        this.node = node;
    }

    @Override
    public long getLength() {
        return this.node.getSize();
    }

    @Override
    public void setLength(long length) throws IOException {
        final long old = getLength();
        if (old == length) return;

        if (length < old) {
            // 收缩：只更新逻辑长度（不回收簇，简化处理）
            node.setSize(length);
            return;
        }

        final int bpc = node.getSuperBlock().getBytesPerCluster();
        final long needClusters = ((length + bpc - 1) / bpc) - node.getClusterCount();
        if (needClusters > 0) {
            allocateClusters(needClusters);
        }
        node.setSize(length);
    }

    private void allocateClusters(long need) throws IOException {
        final ClusterBitMap bitmap = ((ExFatFileSystem) getFileSystem()).getClusterBitmap();

        if (node.getStartCluster() == 0) {
            // 新文件（尚无数据簇）：批量分配 need 个连续簇（findFree 内部已标记 bitmap）
            final long start = bitmap.findFree((int) need);
            node.setStartCluster(start);
            node.setClusterCount(need);
        } else {
            // 扩展已有文件：从末尾继续连续分配
            final long end = node.getStartCluster() + node.getClusterCount() - 1;
            if (!bitmap.isRangeFree(end + 1, (int) need)) {
                throw new IOException("not enough contiguous clusters");
            }
            for (long i = 0; i < need; i++) {
                bitmap.setClusterUsed(end + 1 + i, true);
            }
            node.setClusterCount(node.getClusterCount() + need);
        }
    }

    @Override
    public void read(long offset, ByteBuffer dest) throws IOException {
        final int len = dest.remaining();

        if (len == 0) return;

        if (offset + len > getLength()) {
            throw new EOFException();
        }

        final int bpc = node.getSuperBlock().getBytesPerCluster();
        long cluster = node.getStartCluster();
        int remain = dest.remaining();

        // Skip to the cluster that corresponds to the requested offset
        long clustersToSkip = offset / bpc;
        for (int i = 0; i < clustersToSkip; i++) {
            cluster = this.node.nextCluster(cluster);

            if (Cluster.invalid(cluster)) {
                throw new IOException("invalid cluster");
            }
        }

        // Read in any leading partial cluster
        if (offset % bpc != 0) {
            ByteBuffer tmpBuffer = ByteBuffer.allocate(bpc);
            node.getSuperBlock().readCluster(tmpBuffer, cluster);

            int tmpOffset = (int) (offset % bpc);
            int tmpLength = Math.min(remain, bpc - tmpOffset);

            dest.put(tmpBuffer.array(), tmpOffset, tmpLength);
            remain -= tmpLength;
            cluster = this.node.nextCluster(cluster);

            if (remain != 0 && Cluster.invalid(cluster)) {
                throw new IOException("invalid cluster");
            }
        }

        // Read in the remaining data
        while (remain > 0) {
            int toRead = Math.min(bpc, remain);
            dest.limit(dest.position() + toRead);
            node.getSuperBlock().readCluster(dest, cluster);

            remain -= toRead;
            cluster = this.node.nextCluster(cluster);

            if (remain != 0 && Cluster.invalid(cluster)) {
                throw new IOException("invalid cluster");
            }
        }
    }

    @Override
    public void write(long offset, ByteBuffer src) throws IOException {
        final int len = src.remaining();
        if (len == 0) return;

        // 支持扩展写入：超出当前长度时自动扩展文件
        if (offset + len > getLength()) {
            setLength(offset + len);
        }

        final int bpc = node.getSuperBlock().getBytesPerCluster();
        long cluster = node.getStartCluster();
        int remain = len;

        // Skip to the cluster that corresponds to the requested offset
        long clustersToSkip = offset / bpc;
        for (int i = 0; i < clustersToSkip; i++) {
            cluster = this.node.nextCluster(cluster);
            if (Cluster.invalid(cluster)) {
                throw new IOException("invalid cluster");
            }
        }

        int inClusterOffset = (int) (offset % bpc);
        while (remain > 0) {
            // 整簇且文件连续：批量合并多个连续簇一次性写入（大幅减少 USB 传输次数）
            if (inClusterOffset == 0 && node.isContiguous() && remain >= bpc) {
                int batch = Math.min(MAX_BATCH_CLUSTERS, remain / bpc);
                if (batch < 1) {
                    batch = 1;
                }
                final int writeLen = batch * bpc;
                final byte[] data = new byte[writeLen];
                src.get(data);
                final ByteBuffer buf = ByteBuffer.allocate(writeLen);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                buf.put(data);
                buf.rewind();
                node.getSuperBlock().writeData(buf, cluster);
                remain -= writeLen;
                for (int i = 0; i < batch; i++) {
                    cluster = this.node.nextCluster(cluster);
                    if (Cluster.invalid(cluster)) {
                        throw new IOException("invalid cluster");
                    }
                }
                continue;
            }

            final int chunk = Math.min(bpc - inClusterOffset, remain);
            final boolean fullCluster = (inClusterOffset == 0 && chunk == bpc);

            final byte[] data = new byte[chunk];
            src.get(data);

            if (fullCluster) {
                // 整簇写入：无需先读，直接写（性能关键）
                final ByteBuffer buf = ByteBuffer.allocate(bpc);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                buf.put(data);
                buf.rewind();
                node.getSuperBlock().writeCluster(buf, cluster);
            } else {
                // 部分簇写入：读-改-写
                final ByteBuffer clusterBuf = ByteBuffer.allocate(bpc);
                clusterBuf.order(ByteOrder.LITTLE_ENDIAN);
                node.getSuperBlock().readCluster(clusterBuf, cluster);
                clusterBuf.rewind();

                clusterBuf.position(inClusterOffset);
                clusterBuf.put(data);

                clusterBuf.rewind();
                node.getSuperBlock().writeCluster(clusterBuf, cluster);
            }

            remain -= chunk;
            inClusterOffset = 0;
            if (remain > 0) {
                cluster = this.node.nextCluster(cluster);
                if (Cluster.invalid(cluster)) {
                    throw new IOException("invalid cluster");
                }
            }
        }
    }

    @Override
    public void flush() throws IOException {
        // 把 size / 起始簇等信息写回父目录的目录条目
        final NodeDirectory parent = node.getParentDir();
        if (parent != null && node.getEntryIndex() >= 0) {
            parent.writeBack(node);
        }
    }

}
