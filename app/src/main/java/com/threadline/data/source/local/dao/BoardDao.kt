package com.threadline.data.source.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.threadline.data.source.local.entity.BoardCardEntity
import com.threadline.data.source.local.entity.BoardConnectionEntity
import com.threadline.data.source.local.entity.BoardEntity

@Dao
interface BoardDao {

    @Query("SELECT * FROM boards ORDER BY updatedAt DESC")
    suspend fun getAll(): List<BoardEntity>

    @Query("SELECT * FROM boards WHERE id = :id")
    suspend fun getById(id: String): BoardEntity?

    @Upsert
    suspend fun upsert(board: BoardEntity)

    @Delete
    suspend fun delete(board: BoardEntity)

    @Query("SELECT * FROM board_cards WHERE boardId = :boardId")
    suspend fun getCardsForBoard(boardId: String): List<BoardCardEntity>

    @Upsert
    suspend fun upsertCard(card: BoardCardEntity)

    @Delete
    suspend fun deleteCard(card: BoardCardEntity)

    @Query("SELECT * FROM board_connections WHERE boardId = :boardId")
    suspend fun getConnectionsForBoard(boardId: String): List<BoardConnectionEntity>

    @Upsert
    suspend fun upsertConnection(connection: BoardConnectionEntity)

    @Delete
    suspend fun deleteConnection(connection: BoardConnectionEntity)
}
