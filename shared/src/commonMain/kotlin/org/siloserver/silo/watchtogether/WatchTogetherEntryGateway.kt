package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.flow.StateFlow

interface WatchTogetherEntryGateway {
    val roomSnapshot: StateFlow<RoomSnapshot?>
    suspend fun createRoom(request: CreateRoomRequest): ApiResult<RoomResponse>
    suspend fun joinRoom(request: JoinRoomRequest): ApiResult<RoomResponse>
    suspend fun setSelection(request: SetSelectionRequest): ApiResult<RoomResponse>
}
