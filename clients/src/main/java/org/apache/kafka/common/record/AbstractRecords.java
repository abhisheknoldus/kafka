/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.record;

import org.apache.kafka.common.utils.AbstractIterator;
import org.apache.kafka.common.utils.Utils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public abstract class AbstractRecords implements Records {

    private final Iterable<Record> records = new Iterable<Record>() {
        @Override
        public Iterator<Record> iterator() {
            return recordsIterator();
        }
    };

    @Override
    public boolean hasMatchingMagic(byte magic) {
        for (RecordBatch batch : batches())
            if (batch.magic() != magic)
                return false;
        return true;
    }

    @Override
    public boolean hasCompatibleMagic(byte magic) {
        for (RecordBatch batch : batches())
            if (batch.magic() > magic)
                return false;
        return true;
    }

    /**
     * Convert this message set to a compatible magic format.
     *
     * @param toMagic The maximum magic version to convert to. Batches with larger magic values
     *                will be converted to this magic; batches with equal or lower magic will not
     *                be converted at all.
     */
    @Override
    public Records downConvert(byte toMagic) {
        List<? extends RecordBatch> batches = Utils.toList(batches().iterator());
        if (batches.isEmpty()) {
            // This indicates that the message is too large, which indicates that the buffer is not large
            // enough to hold a full record batch. We just return all the bytes in the file message set.
            // Even though the message set does not have the right format version, we expect old clients
            // to raise an error to the user after reading the message size and seeing that there
            // are not enough available bytes in the response to read the full message.
            return this;
        } else {
            // maintain the batch along with the decompressed records to avoid the need to decompress again
            List<RecordBatchAndRecords> recordBatchAndRecordsList = new ArrayList<>(batches.size());
            int totalSizeEstimate = 0;

            for (RecordBatch batch : batches) {
                if (batch.magic() <= toMagic) {
                    totalSizeEstimate += batch.sizeInBytes();
                    recordBatchAndRecordsList.add(new RecordBatchAndRecords(batch, null, null));
                } else {
                    List<Record> records = Utils.toList(batch.iterator());
                    final long baseOffset;
                    if (batch.magic() >= RecordBatch.MAGIC_VALUE_V2)
                        baseOffset = batch.baseOffset();
                    else
                        baseOffset = records.get(0).offset();
                    totalSizeEstimate += estimateSizeInBytes(toMagic, baseOffset, batch.compressionType(), records);
                    recordBatchAndRecordsList.add(new RecordBatchAndRecords(batch, records, baseOffset));
                }
            }

            ByteBuffer buffer = ByteBuffer.allocate(totalSizeEstimate);
            for (RecordBatchAndRecords recordBatchAndRecords : recordBatchAndRecordsList) {
                if (recordBatchAndRecords.batch.magic() <= toMagic)
                    recordBatchAndRecords.batch.writeTo(buffer);
                else
                    buffer = convertLogEntry(toMagic, buffer, recordBatchAndRecords);
            }

            buffer.flip();
            return MemoryRecords.readableRecords(buffer);
        }
    }

    private ByteBuffer convertLogEntry(byte magic, ByteBuffer buffer, RecordBatchAndRecords recordBatchAndRecords) {
        RecordBatch batch = recordBatchAndRecords.batch;
        final TimestampType timestampType = batch.timestampType();
        long logAppendTime = timestampType == TimestampType.LOG_APPEND_TIME ? batch.maxTimestamp() : RecordBatch.NO_TIMESTAMP;

        MemoryRecordsBuilder builder = MemoryRecords.builder(buffer, magic, batch.compressionType(),
                timestampType, recordBatchAndRecords.baseOffset, logAppendTime);
        for (Record record : recordBatchAndRecords.records) {
            // control messages are only supported in v2 and above, so skip when down-converting
            if (magic < RecordBatch.MAGIC_VALUE_V2 && record.isControlRecord())
                continue;

            if (magic < RecordBatch.MAGIC_VALUE_V2) {
                builder.appendWithOffset(record.offset(), record.timestamp(), record.key(), record.value());
            } else {
                builder.append(record);
            }
        }

        builder.close();
        return builder.buffer();
    }

    /**
     * Get an iterator over the deep records.
     * @return An iterator over the records
     */
    @Override
    public Iterable<Record> records() {
        return records;
    }

    private Iterator<Record> recordsIterator() {
        return new AbstractIterator<Record>() {
            private final Iterator<? extends RecordBatch> batches = batches().iterator();
            private Iterator<Record> records;

            @Override
            protected Record makeNext() {
                if (records != null && records.hasNext())
                    return records.next();

                if (batches.hasNext()) {
                    records = batches.next().iterator();
                    return makeNext();
                }

                return allDone();
            }
        };
    }

    public static int estimateSizeInBytes(byte magic,
                                          long baseOffset,
                                          CompressionType compressionType,
                                          Iterable<Record> records) {
        int size = 0;
        if (magic <= RecordBatch.MAGIC_VALUE_V1) {
            for (Record record : records)
                size += Records.LOG_OVERHEAD + LegacyRecord.recordSize(magic, record.key(), record.value());
        } else {
            size = DefaultRecordBatch.sizeInBytes(baseOffset, records);
        }
        return estimateCompressedSizeInBytes(size, compressionType);
    }

    public static int estimateSizeInBytes(byte magic,
                                          CompressionType compressionType,
                                          Iterable<SimpleRecord> records) {
        int size = 0;
        if (magic <= RecordBatch.MAGIC_VALUE_V1) {
            for (SimpleRecord record : records)
                size += Records.LOG_OVERHEAD + LegacyRecord.recordSize(magic, record.key(), record.value());
        } else {
            size = DefaultRecordBatch.sizeInBytes(records);
        }
        return estimateCompressedSizeInBytes(size, compressionType);
    }

    private static int estimateCompressedSizeInBytes(int size, CompressionType compressionType) {
        return compressionType == CompressionType.NONE ? size : Math.min(Math.max(size / 2, 1024), 1 << 16);
    }

    public static int sizeInBytesUpperBound(byte magic, byte[] key, byte[] value) {
        if (magic >= RecordBatch.MAGIC_VALUE_V2)
            return DefaultRecordBatch.batchSizeUpperBound(key, value, null);
        else
            return Records.LOG_OVERHEAD + LegacyRecord.recordSize(magic, key, value);
    }

    private static class RecordBatchAndRecords {
        private final RecordBatch batch;
        private final List<Record> records;
        private final Long baseOffset;

        private RecordBatchAndRecords(RecordBatch batch, List<Record> records, Long baseOffset) {
            this.batch = batch;
            this.records = records;
            this.baseOffset = baseOffset;
        }
    }

}
