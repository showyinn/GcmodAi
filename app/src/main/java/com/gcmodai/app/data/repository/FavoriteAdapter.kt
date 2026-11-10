package com.gcmodai.app.data.repository

import com.gcmodai.app.data.db.entity.FavoriteEntity
import com.gcmodai.app.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
