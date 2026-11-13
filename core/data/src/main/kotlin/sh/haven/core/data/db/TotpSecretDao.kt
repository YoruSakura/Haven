package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.TotpSecret

@Dao
interface TotpSecretDao {

    @Query("SELECT * FROM totp_secrets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TotpSecret>>

    @Query("SELECT * FROM totp_secrets ORDER BY createdAt DESC")
    suspend fun getAll(): List<TotpSecret>

    @Query("SELECT * FROM totp_secrets WHERE id = :id")
    suspend fun getById(id: String): TotpSecret?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(secret: TotpSecret)

    /**
     * Rename in place, touching only the label.
     *
     * Deliberately not a read-modify-write through the repository's `save`: that re-encrypts
     * [TotpSecret.secret], and the repository's decrypt falls back to the stored value when
     * decryption fails — so renaming a row whose secret could not be decrypted would encrypt
     * the ciphertext a second time and destroy it. A targeted UPDATE cannot.
     */
    @Query("UPDATE totp_secrets SET label = :label WHERE id = :id")
    suspend fun rename(id: String, label: String)

    @Query("DELETE FROM totp_secrets WHERE id = :id")
    suspend fun deleteById(id: String)
}
