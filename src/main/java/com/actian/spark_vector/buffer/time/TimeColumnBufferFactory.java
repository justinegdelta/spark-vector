/*
 * Copyright 2016 Actian Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.actian.spark_vector.buffer.time;

import static com.actian.spark_vector.buffer.time.TimeConversion.normalizedTime;

import java.nio.ByteBuffer;

import com.actian.spark_vector.buffer.ColumnBuffer;
import com.actian.spark_vector.buffer.ColumnBufferFactory;
import com.actian.spark_vector.buffer.time.TimeConversion.TimeConverter;
import java.sql.Timestamp;

abstract class TimeColumnBufferFactory extends ColumnBufferFactory {
    @Override
    protected abstract TimeColumnBuffer createColumnBufferInternal(String name, int index, String type, int precision, int scale, boolean nullable,
            int maxRowCount);

    protected abstract TimeConverter createConverter();

    public static abstract class TimeColumnBuffer extends ColumnBuffer<Timestamp> {

        private final int scale;
        private final TimeConverter converter;
        private final boolean adjustToUTC;

        protected TimeColumnBuffer(int valueCount, int valueWidth, String name, int index, int scale, boolean nullable, TimeConverter converter,
                boolean adjustToUTC) {
            super(valueCount, valueWidth, valueWidth, name, index, nullable);
            this.scale = scale;
            this.converter = converter;
            this.adjustToUTC = adjustToUTC;
        }

        @Override
        protected final void bufferNextValue(Timestamp source, ByteBuffer buffer) {
            if (adjustToUTC)
                TimeConversion.convertLocalTimeStampToUTC(source);
            System.out.println("Trying to serialize value " + source + " with time " + source.getTime() + " to "
                    + converter.convert(normalizedTime(source), scale));
            bufferNextConvertedValue(converter.convert(normalizedTime(source), scale), buffer);
        }

        protected abstract void bufferNextConvertedValue(long converted, ByteBuffer buffer);
    }
}
