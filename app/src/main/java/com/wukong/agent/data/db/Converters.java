package com.wukong.agent.data.db;

import androidx.room.TypeConverter;

public class Converters {

    @TypeConverter
    public static Boolean fromInt(Integer value) {
        return value == null ? null : value != 0;
    }

    @TypeConverter
    public static Integer fromBoolean(Boolean value) {
        return value == null ? null : (value ? 1 : 0);
    }
}
