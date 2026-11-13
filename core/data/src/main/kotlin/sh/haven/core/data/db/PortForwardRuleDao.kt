package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.PortForwardRule

@Dao
interface PortForwardRuleDao {

    @Query("SELECT * FROM port_forward_rules WHERE profileId = :profileId")
    fun observeForProfile(profileId: String): Flow<List<PortForwardRule>>

    @Query("SELECT * FROM port_forward_rules")
    suspend fun getAll(): List<PortForwardRule>

    @Query("SELECT * FROM port_forward_rules")
    fun observeAll(): Flow<List<PortForwardRule>>

    @Query("SELECT * FROM port_forward_rules WHERE profileId = :profileId AND enabled = 1")
    suspend fun getEnabledForProfile(profileId: String): List<PortForwardRule>

    /** Stop the reverse forwards created by the removed agent endpoint. */
    @Query("UPDATE port_forward_rules SET enabled = 0 WHERE type = 'REMOTE' AND bindAddress = '127.0.0.1' AND bindPort = 8730 AND targetHost = '127.0.0.1' AND targetPort = 8730")
    suspend fun disableLegacyAgentForwards()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: PortForwardRule)

    @Query("DELETE FROM port_forward_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}
