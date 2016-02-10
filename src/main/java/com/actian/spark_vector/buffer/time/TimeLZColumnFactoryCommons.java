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
import static com.actian.spark_vector.buffer.time.TimeConversion.scaledTime;

import java.util.Calendar;
import java.util.Date;

import com.actian.spark_vector.buffer.time.TimeConversion.TimeConverter;

final class TimeLZColumnFactoryCommons {
    private static final String TIME_LZ_TYPE_ID = "time with local time zone";

    private TimeLZColumnFactoryCommons() {
        throw new IllegalStateException();
    }

    public static boolean isSupportedColumnType(String columnTypeName, int columnScale, int minScale, int maxScale) {
        return columnTypeName.equalsIgnoreCase(TIME_LZ_TYPE_ID) && minScale <= columnScale && columnScale <= maxScale;
    }

    public static final class TimeLZConverter extends TimeConverter {

        @Override
        public long convert(long nanos, int scale) {
            return scaledTime(nanos, scale);
        }
    }
}
