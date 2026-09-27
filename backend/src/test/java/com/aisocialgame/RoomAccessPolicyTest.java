package com.aisocialgame;

import com.aisocialgame.model.*;
import com.aisocialgame.repository.*;
import com.aisocialgame.service.RoomAccessPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomAccessPolicyTest {
    @Test void newLobbyMemberCannotReadPreviousPrivateGameButCanPrepareForNextGame() {
        var rooms=mock(RoomRepository.class);var states=mock(GameStateRepository.class);
        var policy=new RoomAccessPolicy(rooms,states);
        var room=new Room("room","turtle-soup","room",RoomStatus.WAITING,6,true,"hash","text",Map.of());
        room.setHostUserId("owner");
        room.setSeats(List.of(new RoomSeat(0,"new-player","new",false,null,"",true,false)));
        var state=new GameState("room","turtle-soup","SETTLEMENT");
        state.setPlayers(List.of(new GamePlayerState("old-player","old",0,false,null,"")));
        RoomAccessPolicy.snapshot(state,room);
        when(rooms.findById("room")).thenReturn(Optional.of(room));
        when(states.findById("room")).thenReturn(Optional.of(state));
        assertDoesNotThrow(()->policy.requireLobbyRead("room","new-player"));
        assertDoesNotThrow(()->policy.requireParticipant("room","new-player"));
        assertDoesNotThrow(()->policy.requireStateSubscription("room","new-player"));
        assertThrows(com.aisocialgame.exception.ApiException.class,()->policy.requireRead("room","new-player"));
        assertDoesNotThrow(()->policy.requireRead("room","old-player"));
        assertThrows(com.aisocialgame.exception.ApiException.class,()->policy.requireLobbyRead("room","stranger"));
        room.setPrivate(false);
        assertThrows(com.aisocialgame.exception.ApiException.class,()->policy.requireRead("room","stranger"));
    }
}
