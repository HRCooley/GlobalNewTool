package com.threadline.data.source.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "boards")
data class BoardEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val linkedThreadId: String?
)

@Entity(tableName = "board_cards")
data class BoardCardEntity(
    @PrimaryKey val id: String,
    val boardId: String,
    val type: String,
    val title: String,
    val notes: String?,
    val color: String?,
    val positionX: Float?,
    val positionY: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val sourceUrl: String?,
    val sourceTitle: String?,
    val sourceSnippet: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "board_connections")
data class BoardConnectionEntity(
    @PrimaryKey val id: String,
    val boardId: String,
    val fromCardId: String,
    val toCardId: String,
    val label: String,
    val notes: String?,
    val createdAt: Long
)
