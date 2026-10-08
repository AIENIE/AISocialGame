package com.aisocialgame;

import com.aisocialgame.model.*;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = AiSocialGameApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomDiscoveryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired RoomService service;
    @Autowired RoomRepository rooms;
    @MockitoBean AuthService auth;
    @MockitoSpyBean RoomCodeGenerator codes;
    @AfterEach void resetCodes() { reset(codes); }
    User user() { User u = new User(); u.setId(UUID.randomUUID().toString()); u.setNickname("测试用户"); return u; }
    Room room(User owner) { return service.createRoom("undercover", "私密测试", true, "secret", "text", Map.of("playerCount",4), owner); }

    @Test void authenticatedDiscoveryIsMinimalAndPasswordAdmissionWorks() throws Exception {
        User owner = user(), guest = user(); Room r = room(owner);
        when(auth.authenticate("test-guest")).thenReturn(guest);
        mvc.perform(get("/api/rooms/search").param("roomCode",r.getRoomCode())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/rooms/search").param("roomCode",r.getRoomCode()).header("X-Auth-Token","test-guest"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.isPrivate").value(true)).andExpect(jsonPath("$.joined").value(false))
            .andExpect(jsonPath("$.seats").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.config").doesNotExist());
        String base = "/api/games/undercover/rooms/"+r.getId();
        mvc.perform(get(base).header("X-Auth-Token","test-guest")).andExpect(status().isForbidden());
        mvc.perform(get(base+"/entry").header("X-Auth-Token","test-guest")).andExpect(status().isOk());
        mvc.perform(post(base+"/join").header("X-Auth-Token","test-guest").contentType("application/json").content("{\"displayName\":\"guest\",\"password\":\"wrong\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROOM_PASSWORD_INVALID"));
        mvc.perform(post(base+"/join").header("X-Auth-Token","test-guest").contentType("application/json").content("{\"displayName\":\"guest\",\"password\":\"secret\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.selfPlayerId").value(guest.getId()));
        mvc.perform(get(base).header("X-Auth-Token","test-guest")).andExpect(status().isOk()).andExpect(jsonPath("$.isPrivate").value(true));
        mvc.perform(get("/api/games/werewolf/rooms/"+r.getId()+"/entry").header("X-Auth-Token","test-guest")).andExpect(status().isNotFound());
    }

    @Test void expiredHttpReadsAndWritesAreGoneAndCannotReviveRoom() throws Exception {
        User owner = user(); Room r = room(owner); when(auth.authenticate("test-owner")).thenReturn(owner);
        r.setWaitingSince(LocalDateTime.now().minusHours(4)); rooms.saveAndFlush(r);
        String base = "/api/games/undercover/rooms/"+r.getId();
        for (String suffix : List.of("", "/entry", "/state", "/logs")) mvc.perform(get(base+suffix).header("X-Auth-Token","test-owner"))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ROOM_EXPIRED"));
        mvc.perform(get("/api/rooms/search").param("roomCode",r.getRoomCode()).header("X-Auth-Token","test-owner")).andExpect(status().isGone());
        for (String suffix : List.of("/start", "/join")) mvc.perform(post(base+suffix).header("X-Auth-Token","test-owner").contentType("application/json").content("{\"displayName\":\"guest\"}"))
            .andExpect(status().isGone());
        assertEquals(RoomStatus.EXPIRED, rooms.findById(r.getId()).orElseThrow().getStatus());
    }

    @Test void allocationRetriesDatabaseUniqueConflictWithoutOverwritingOldRoom() {
        User owner = user(); Room existing = room(owner); String available = "999999";
        while (rooms.existsByRoomCode(available)) available = String.valueOf(Integer.parseInt(available)-1);
        clearInvocations(codes);
        doReturn(existing.getRoomCode(), available).when(codes).next();
        Room created = room(owner);
        assertEquals(available, created.getRoomCode()); assertNotEquals(existing.getId(), created.getId());
        assertEquals(existing.getId(), rooms.findByRoomCode(existing.getRoomCode()).orElseThrow().getId());
        verify(codes,times(2)).next();
    }
}
