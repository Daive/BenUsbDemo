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

/**
 * The exFAT free space bitmap.
 *
 * @author Matthias Treydte &lt;waldheinz at gmail.com&gt;
 */
public final class ClusterBitMap {

    public static ClusterBitMap read(ExFatSuperBlock sb,
                                     long startCluster, long size) throws IOException {

        Cluster.checkValid(startCluster);

        final ClusterBitMap result = new ClusterBitMap(sb, startCluster, size);

        if (size < ((result.clusterCount + 7) / 8)) {
            throw new IOException("cluster bitmap too small");
        }

        return result;
    }

    /**
     * The super block of the file system holding this {@code ClusterBitMap}.
     */
    private final ExFatSuperBlock sb;

    /**
     * The first cluster of the {@code ClusterBitMap}.
     */
    private final long startCluster;

    /**
     * The size in bytes.
     */
    private final long size;

    private final long clusterCount;
    private final long devOffset;
    private final DeviceAccess da;

    private ClusterBitMap(
        ExFatSuperBlock sb, long startCluster, long size)
        throws IOException {

        this.sb = sb;
        this.da = sb.getDeviceAccess();
        this.startCluster = startCluster;
        this.size = size;
        this.clusterCount = sb.getClusterCount() - Cluster.FIRST_DATA_CLUSTER;
        this.devOffset = sb.clusterToOffset(startCluster);
    }

    public boolean isClusterFree(long cluster) throws IOException {
        Cluster.checkValid(cluster, this.sb);

        final long bitNum = cluster - Cluster.FIRST_DATA_CLUSTER;
        final long offset = bitNum / 8;
        final int bits = this.da.getUint8(offset + this.devOffset);
        return (bits & (1 << (bitNum % 8))) == 0;
    }

    /**
     * Allocates the first free cluster and marks it as used.
     *
     * @return the allocated cluster number
     * @throws IOException if no free cluster exists
     */
    public long allocate() throws IOException {
        return findFree(1);
    }

    /**
     * 批量读取 bitmap，在内存中寻找连续的 {@code need} 个空闲簇并标记为已用。
     *
     * @param need number of contiguous clusters needed
     * @return the first cluster of the allocated range
     * @throws IOException if not enough contiguous free clusters exist
     */
    public long findFree(int need) throws IOException {
        final long totalBytes = (this.clusterCount + 7) / 8;
        final int bpc = sb.getBytesPerCluster();

        long cluster = this.startCluster;
        long bitIndex = 0;
        long run = 0;
        long runStart = -1;

        long remaining = totalBytes;
        while (remaining > 0) {
            final int chunkSize = (int) Math.min(bpc, remaining);
            final ByteBuffer buf = ByteBuffer.allocate(chunkSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            da.read(buf, sb.clusterToOffset(cluster));
            buf.rewind();

            for (int i = 0; i < chunkSize; i++) {
                final int byteVal = buf.get(i) & 0xff;
                for (int b = 0; b < 8 && bitIndex < this.clusterCount; b++) {
                    final boolean free = (byteVal & (1 << b)) == 0;
                    if (free) {
                        if (run == 0) {
                            runStart = bitIndex;
                        }
                        run++;
                        if (run >= need) {
                            final long first = Cluster.FIRST_DATA_CLUSTER + runStart;
                            markRangeUsed(first, need);
                            return first;
                        }
                    } else {
                        run = 0;
                    }
                    bitIndex++;
                }
            }

            remaining -= chunkSize;
            cluster++;
        }

        throw new IOException("no free cluster");
    }

    /**
     * 检查从 {@code startCluster} 开始的 {@code count} 个簇是否全部空闲。
     */
    public boolean isRangeFree(long startCluster, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            if (!isClusterFree(startCluster + i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 批量将 {@code count} 个连续簇标记为已用（整块读-改-写 bitmap，
     * 避免逐字节 USB 写导致性能问题）。
     */
    public void markRangeUsed(long startCluster, long count) throws IOException {
        final long firstBit = startCluster - Cluster.FIRST_DATA_CLUSTER;
        final int bpc = sb.getBytesPerCluster();
        final long totalBytes = (this.clusterCount + 7) / 8;

        // 读整个 bitmap 到内存
        final byte[] bits = new byte[(int) totalBytes];
        long cluster = this.startCluster;
        int off = 0;
        while (off < bits.length) {
            final int chunk = Math.min(bpc, bits.length - off);
            final ByteBuffer buf = ByteBuffer.allocate(chunk);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            da.read(buf, sb.clusterToOffset(cluster));
            buf.rewind();
            buf.get(bits, off, chunk);
            off += chunk;
            cluster++;
        }

        // 内存中标记位
        for (long i = 0; i < count; i++) {
            final long bit = firstBit + i;
            bits[(int) (bit / 8)] |= (1 << (bit % 8));
        }

        // 写回整个 bitmap
        cluster = this.startCluster;
        off = 0;
        while (off < bits.length) {
            final int chunk = Math.min(bpc, bits.length - off);
            final ByteBuffer buf = ByteBuffer.allocate(chunk);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.put(bits, off, chunk);
            buf.rewind();
            da.write(buf, sb.clusterToOffset(cluster));
            off += chunk;
            cluster++;
        }
    }

    /**
     * Marks a cluster as used or free and writes the bitmap back to disk.
     *
     * @param cluster the cluster number
     * @param used    {@code true} to mark used, {@code false} to mark free
     * @throws IOException on write error
     */
    public void setClusterUsed(long cluster, boolean used) throws IOException {
        Cluster.checkValid(cluster, this.sb);

        final long bitNum = cluster - Cluster.FIRST_DATA_CLUSTER;
        final long offset = bitNum / 8;
        final int oldBits = this.da.getUint8(offset + this.devOffset);
        final int newBits;
        if (used) {
            newBits = oldBits | (1 << (bitNum % 8));
        } else {
            newBits = oldBits & ~(1 << (bitNum % 8));
        }

        final ByteBuffer buf = ByteBuffer.allocate(1);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) newBits);
        buf.rewind();
        da.write(buf, offset + this.devOffset);
    }

    /**
     * Gets the first cluster of the bitmap.
     *
     * @return the first cluster.
     */
    public long getStartCluster() {
        return startCluster;
    }

    /**
     * Gets the cluster count.
     *
     * @return the cluster count.
     */
    public long getClusterCount() {
        return clusterCount;
    }

    public long getUsedClusterCount() throws IOException {
        long result = 0;

        for (long i = 0; i < size; i++) {
            final int bits = this.da.getUint8(this.devOffset + i);
            result += Integer.bitCount(bits);
        }

        return result;
    }

}
