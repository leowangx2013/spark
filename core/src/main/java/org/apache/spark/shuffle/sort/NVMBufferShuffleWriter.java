/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.sort;

import java.io.IOException;
import java.util.ArrayList;
import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Enumeration;
import java.util.Vector;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.spark.serializer.DeserializationStream;
import org.apache.spark.util.SystemClock;
import scala.collection.JavaConverters.*;
import org.apache.spark.shuffle.NVMBufferShuffleBlockResolver;
import scala.None$;
import scala.Option;
import scala.Product2;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.reflect.ClassTag;
import scala.reflect.ClassTag$;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Closeables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.Partitioner;
import org.apache.spark.ShuffleDependency;
import org.apache.spark.SparkConf;
import org.apache.spark.TaskContext;
import org.apache.spark.executor.ShuffleWriteMetrics;
import org.apache.spark.scheduler.MapStatus;
import org.apache.spark.scheduler.MapStatus$;
import org.apache.spark.serializer.Serializer;
import org.apache.spark.serializer.SerializerInstance;
import org.apache.spark.shuffle.ShuffleWriter;
import org.apache.spark.storage.*;
import org.apache.spark.util.Utils;

/**
 * This class implements sort-based shuffle's hash-style shuffle fallback path. This write path
 * writes incoming records to separate files, one file per reduce partition, then concatenates these
 * per-partition files to form a single output file, regions of which are served to reducers.
 * Records are not buffered in memory. This is essentially identical to
 * {@link org.apache.spark.shuffle.hash.HashShuffleWriter}, except that it writes output in a format
 * that can be served / consumed via {@link org.apache.spark.shuffle.IndexShuffleBlockResolver}.
 * <p>
 * This write path is inefficient for shuffles with large numbers of reduce partitions because it
 * simultaneously opens separate serializers and file streams for all partitions. As a result,
 * {@link SortShuffleManager} only selects this write path when
 * <ul>
 *    <li>no Ordering is specified,</li>
 *    <li>no Aggregator is specific, and</li>
 *    <li>the number of partitions is less than
 *      <code>spark.shuffle.sort.bypassMergeThreshold</code>.</li>
 * </ul>
 *
 * This code used to be part of {@link org.apache.spark.util.collection.ExternalSorter} but was
 * refactored into its own class in order to reduce code complexity; see SPARK-7855 for details.
 * <p>
 * There have been proposals to completely remove this code path; see SPARK-6026 for details.
 */

final class NVMBufferShuffleWriter<K, V> extends ShuffleWriter<K, V> {
    private final Logger logger = LoggerFactory.getLogger(NVMBufferShuffleWriter.class);

    private final int numPartitions;
    private final BlockManager blockManager;
    private final Partitioner partitioner;
    private final ShuffleWriteMetrics writeMetrics;
    private final int shuffleId;
    private final int mapId;
    private final Serializer serializer;
    private final NVMBufferShuffleBlockResolver shuffleBlockResolver;

    /**
     * Array of NVM Buffer writers, one for each partition
     */
    private NVMBufferObjectWriter[] partitionWriters;
    @Nullable private MapStatus mapStatus;
    private long[] partitionLengths;

    /**
     * Are we in the process of stopping? Because map tasks can call stop() with success = true
     * and then call stop() with success = false if they get an exception, we want to make sure
     * we don't try deleting files, etc twice.
     */
    private boolean stopping = false;

    public int equal(int index, java.util.List<ByteBuf> list) {
        System.out.println("index " + index);
        int i = 0;
        int total = 0;
        int size = list.size() - 1;
        for (ByteBuf ele: list) {
            total += ele.writerIndex();
            if (index == total && i != size) {
                System.out.println("index " + index + " ele.writerIndex() " + ele.writerIndex() + " total " + total);
                return 0;
            }
            i++;
        }
        return -1;
    }

    public NVMBufferShuffleWriter(
            BlockManager blockManager,
            NVMBufferShuffleBlockResolver shuffleBlockResolver,
            NVMBufferShuffleHandle<K, V> handle,
            int mapId,
            TaskContext taskContext,
            SparkConf conf) {
        // Use getSizeAsKb (not bytes) to maintain backwards compatibility if no units are provided
        this.blockManager = blockManager;
        final ShuffleDependency<K, V, V> dep = handle.dependency();
        this.mapId = mapId;
        this.shuffleId = dep.shuffleId();
        this.partitioner = dep.partitioner();
        this.numPartitions = partitioner.numPartitions();
        this.writeMetrics = new ShuffleWriteMetrics();
        taskContext.taskMetrics().shuffleWriteMetrics_$eq(Option.apply(writeMetrics));
        this.serializer = Serializer.getSerializer(dep.serializer());
        this.shuffleBlockResolver = shuffleBlockResolver;
    }

    @Override
    public void write(Iterator<Product2<K, V>> records) throws IOException {
        assert (partitionWriters == null);
        if (!records.hasNext()) {
            partitionLengths = new long[numPartitions];
            shuffleBlockResolver.writeNVMBufferAndCommit(shuffleId, mapId, partitionLengths);
            mapStatus = MapStatus$.MODULE$.apply(blockManager.shuffleServerId(), partitionLengths);
            return;
        }

        final SerializerInstance serInstance = serializer.newInstance();

        final long openStartTime = System.nanoTime();
        partitionWriters = new NVMBufferObjectWriter[numPartitions];
        for (int i = 0; i < numPartitions; i++) {
            partitionWriters[i] =
                    blockManager.getNVMBufferWriter(shuffleId, mapId, i, blockManager, serInstance, writeMetrics).open();
        }
        // Creating the file to write to and creating a disk writer both involve interacting with
        // the disk, and can take a long time in aggregate when we open many files, so should be
        // included in the shuffle write time.
        writeMetrics.incShuffleWriteTime(System.nanoTime() - openStartTime);
        // System.out.println("mapid write@panda " + mapId);
        while (records.hasNext()) {
            final Product2<K, V> record = records.next();
            final K key = record._1();
            // partitionWriters[partitioner.getPartition(key)].write(key, record._2());
            // partitionWriters[partitioner.getPartition(key)].writenvmbuffer(key, record._2());
            // partitionWriters[partitioner.getPartition(key)].writebetternvmbuffer(key, record._2());
            // partitionWriters[partitioner.getPartition(key)].writeusenvmbuffer(key, record._2());
            partitionWriters[partitioner.getPartition(key)].writekryonvmbuffer(key, record._2());
            // partitionWriters[partitioner.getPartition(key)].writejavanvmbuffer(key, record._2());
        }
        /*
        System.out.println("write done");

        int totalwindx = 0;
        int totalcapacity = 0;
        for (int i = 0; i < numPartitions; i++) {
            int size = partitionWriters[i].arraylist().size();
            System.out.println("mapid " + mapId + " partiiton " + i + " size " + size);
            for (int j = 0; j < size; j++) {
                totalwindx += partitionWriters[i].arraylist().get(j).writerIndex();
                totalcapacity += partitionWriters[i].arraylist().get(j).capacity();
            }
        }
        System.out.println("map@panda " + mapId + " windx " + totalwindx + " capacity " + totalcapacity);

        for (int i = 0; i < numPartitions; i++) {
            int size = partitionWriters[i].arraylist().size();
            System.out.println("partitionWriters[i].arraylist().size@panda " + size);
            ByteBuf bytebufs = Unpooled.wrappedBuffer(size + 1, partitionWriters[i].arraylist().toArray(new ByteBuf[0]));
            if (bytebufs instanceof CompositeByteBuf) {
                System.out.println("CompositeByteBuf@panda");
                java.util.List<ByteBuf> list = ((CompositeByteBuf) bytebufs).decompose(0, bytebufs.readableBytes());
                for (int j = 0; j < list.size(); j++) {
                    System.out.println(" rindx@panda " + list.get(j).readerIndex() + " windx@panda " + list.get(j).writerIndex());
                }
                ByteBufInputStream deserbytebuf = new ByteBufInputStream(bytebufs);
                DeserializationStream objIn = serInstance.deserializeStream(deserbytebuf);
                // Iterator<Tuple2<Object, Object>> kviter = objIn.asKeyValueIterator();
                Iterator<Tuple2<Object, Object>> kviter = objIn.asNVMBufferKeyValueIterator(deserbytebuf, bytebufs, list);
                System.out.println("rindx@panda before while " + bytebufs.readerIndex() + " windx " + bytebufs.writerIndex());
                while (kviter.hasNext()) {
                    final Tuple2<Object, Object> kv = kviter.next();
                    System.out.println("rindx@panda next() " + bytebufs.readerIndex() + " windx " + bytebufs.writerIndex());
                    final K k = (K) kv._1();
                    System.out.println("k@panda " + k);
                    if(equal(bytebufs.readerIndex(), list) >= 0) {
                        System.out.println("skip int");
                        deserbytebuf.readInt();
                    }
                }
                System.out.println("read done!");
            } else {
                System.out.println("ByteBuf@panda");
                ByteBufInputStream deserbytebuf = new ByteBufInputStream(bytebufs);
                DeserializationStream objIn = serInstance.deserializeStream(deserbytebuf);
                Iterator<Tuple2<Object, Object>> kviter = objIn.asKeyValueIterator();
                System.out.println("rindx@panda before while " + bytebufs.readerIndex() + " windx " + bytebufs.writerIndex());
                while (kviter.hasNext()) {
                    System.out.println("rindx@panda before next() " + bytebufs.readerIndex() + " windx " + bytebufs.writerIndex());
                    final Tuple2<Object, Object> kv = kviter.next();
                    System.out.println("rindx@panda after next() " + bytebufs.readerIndex() + " windx " + bytebufs.writerIndex());
                    final K k = (K) kv._1();
                    final V v = (V) kv._2();
                }
                System.out.println("read done!");
            }
        }
        */
        for (NVMBufferObjectWriter writer : partitionWriters) {
            writer.commitAndClose();
        }
        partitionLengths = createNVMBufferlength();
        // shuffleBlockResolver.writeNVMBufferAndCommit(shuffleId, mapId, partitionLengths);
        mapStatus = MapStatus$.MODULE$.apply(blockManager.shuffleServerId(), partitionLengths);
    }

    @VisibleForTesting
    long[] getPartitionLengths() {
        return partitionLengths;
    }

    /**
     * @return array of lengths, in bytes, of each partition of NVMBuffer (used by map output tracker).
     */
    private long[] createNVMBufferlength() throws IOException {
        // Track location of the partition starts in the output file
        final long[] lengths = new long[numPartitions];
        if (partitionWriters == null) {
            // We were passed an empty iterator
            return lengths;
        }

        for (int i = 0; i < numPartitions; i++) {
            lengths[i] = 0;
            ShuffleBlockId id = new ShuffleBlockId(shuffleId, mapId, i);
            ArrayList<ByteBuf> list = blockManager.nvmbufferManager().get(id.toString());
            int size = list.size();
            if (size != 0) {
                for (int j = 0; j < size; j++) {
                    lengths[i] += list.get(j).readableBytes();
                }
            }
        }
        partitionWriters = null;
        return lengths;
    }

    @Override
    public Option<MapStatus> stop(boolean success) {
        if (stopping) {
            return None$.empty();
        } else {
            stopping = true;
            if (success) {
                if (mapStatus == null) {
                    throw new IllegalStateException("Cannot call stop(true) without having called write()");
                }
                return Option.apply(mapStatus);
            } else {
                // The map task failed, so delete our output data.
                if (partitionWriters != null) {
                    try {
                        for (NVMBufferObjectWriter writer : partitionWriters) {
                            // This method explicitly does _not_ throw exceptions:
                            writer.cleararraylist();
                            // System.out.println(" write size@panda " + writer.arraylist().size());
                        }
                    } finally {
                        partitionWriters = null;
                    }
                }
                shuffleBlockResolver.removeDataByMap(shuffleId, mapId, numPartitions);
                return None$.empty();
            }
        }
    }
}
