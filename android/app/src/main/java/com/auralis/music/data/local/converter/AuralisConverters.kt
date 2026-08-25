package com.auralis.music.data.local.converter

import androidx.room.TypeConverter
import com.auralis.music.domain.model.TrackSource

class AuralisConverters {
    @TypeConverter
    fun fromTrackSource(source: TrackSource?): String {
        return source?.name ?: TrackSource.YOUTUBE.name
    }

    @TypeConverter
    fun toTrackSource(value: String?): TrackSource {
        return try {
            if (value != null) TrackSource.valueOf(value) else TrackSource.YOUTUBE
        } catch (e: Exception) {
            TrackSource.YOUTUBE
        }
    }
}
