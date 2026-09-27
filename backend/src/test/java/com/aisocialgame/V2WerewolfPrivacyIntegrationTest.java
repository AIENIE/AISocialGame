package com.aisocialgame;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.dto.ws.PrivateEvent;
import com.aisocialgame.engine.v2.LegalAction;
import com.aisocialgame.engine.v2.RuleSupport;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.model.AiTurnJob;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.RoomSeat;
import com.aisocialgame.model.RoomStatus;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.AiTurnJobRepository;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.service.ai.v2.AiTurnDecision;
import com.aisocialgame.service.ai.v2.VisibleObservation;
import com.aisocialgame.service.v2.AiTurnCoordinator;
import com.aisocialgame.service.v2.V2GameService;
import com.aisocialgame.websocket.GamePushService;
import com.aisocialgame.websocket.PlayerConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real transactions and JSON persistence, with transport and chargeable model calls mocked. */
@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class V2WerewolfPrivacyIntegrationTest {
    @Autowired V2GameService runtime;
    @Autowired AiTurnCoordinator coordinator;
    @Autowired RoomRepository rooms;
    @Autowired GameStateRepository states;
    @Autowired AiTurnJobRepository jobs;
    @MockitoBean AiGrpcClient client;
    @MockitoBean GamePushService push;
    @MockitoBean PlayerConnectionService connections;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach void keepConnectionsStable() {
        when(connections.isOnline(anyString())).thenReturn(true);
    }

    @Test void privateNightStepsDoNotChangeObserverTokensOrPublicSnapshot() throws Exception {
        Room room = startHumanRoom();
        GameState initial = state(room);
        String villager = initial.getPlayers().stream().filter(p -> "VILLAGER".equals(p.getRole()))
                .findFirst().orElseThrow().getPlayerId();
        String publicSnapshot = json.writeValueAsString(view(room, null));
        String villagerSnapshot = json.writeValueAsString(view(room, villager));
        int initialSerial = RuleSupport.number(initial.getData().get("phaseSerial"), 0);

        assertPrivateWindow(view(room, null));
        assertTrue(token(view(room, null)).matches("[0-9a-f]{64}"));
        assertTrue(token(view(room, villager)).matches("[0-9a-f]{64}"));
        assertTrue(view(room, null).getPlayers().stream().allMatch(p -> p.getRole() == null));
        String actorToken = token(view(room, nightActor(initial)));
        String archiveId = RuleSupport.text(view(room, null).getExtra().get("archiveId"));
        for (int guess = 0; guess < 128; guess++) {
            String publicInputs = archiveId + ":" + guess + ":" + nightActor(initial);
            String enumerableToken = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicInputs.getBytes(StandardCharsets.UTF_8)));
            assertNotEquals(enumerableToken, actorToken, "an acting player cannot recover the private step counter from public inputs");
        }
        clearInvocations(push);

        // The two wolves and then the seer act. The witch still has a turn, so it remains night.
        for (int step = 0; step < 3; step++) {
            String actor = nightActor(state(room));
            assertFalse(legalActions(view(room, actor)).isEmpty());
            submit(room, actor, RuleSupport.action("SKIP", "", null));
            GameState advanced = state(room);
            assertEquals("NIGHT", advanced.getPhase());
            assertEquals(initialSerial + step + 1, RuleSupport.number(advanced.getData().get("phaseSerial"), 0));
            assertEquals(publicSnapshot, json.writeValueAsString(view(room, null)), "a spectator cannot count private actions by polling");
            assertEquals(villagerSnapshot, json.writeValueAsString(view(room, villager)), "a waiting villager receives no private step changes");
        }

        verify(push, never()).pushStateChange(anyString(), any());
        verify(push, never()).pushPrivate(eq(villager), any());
        verifyNoInteractions(client);
    }

    @Test void actedHumanWolfGetsRefreshWhenLaterAiTeammateAddsPrivateSuggestion() throws Exception {
        Room room = startHumanRoom();
        List<GamePlayerState> wolves = state(room).getPlayers().stream().filter(p -> "WEREWOLF".equals(p.getRole()))
                .sorted(Comparator.comparingInt(GamePlayerState::getSeatNumber)).toList();
        String humanWolf = wolves.get(0).getPlayerId();
        String aiWolf = wolves.get(1).getPlayerId();
        assertEquals(humanWolf, nightActor(state(room)));
        submit(room, humanWolf, RuleSupport.action("SKIP", "我想先听听队友的判断。", null));
        assertTrue(legalActions(view(room, humanWolf)).isEmpty(), "the first wolf has already spent its action");

        // Convert the second wolf to an AI seat only after the random deal is known.
        GameState current = state(room);
        GamePlayerState teammate = current.getPlayers().stream().filter(p -> aiWolf.equals(p.getPlayerId())).findFirst().orElseThrow();
        teammate.setAi(true);
        teammate.setPersonaId("ai1");
        states.saveAndFlush(current);
        runtime.tick(room.getId());
        AiTurnJob queued = jobs.findAll().stream().filter(j -> room.getId().equals(j.getRoomId()) && "QUEUED".equals(j.getStatus()))
                .findFirst().orElseThrow();
        assertEquals(aiWolf, queued.getActorId());
        AiTurnJob claimed = coordinator.claim(queued.getId());
        assertNotNull(claimed);
        VisibleObservation observation = json.convertValue(claimed.getObservation(), VisibleObservation.class);
        LegalAction kill = observation.legalActions().stream().filter(a -> "WOLF_KILL".equals(a.nightAction())).findFirst().orElseThrow();
        String suggestion = "我担心有人会守同一目标，想换一个刀口。";
        PlayerAction action = RuleSupport.action("NIGHT_ACTION", suggestion, kill.targets().get(0));
        action.setNightAction("WOLF_KILL");
        String publicBefore = json.writeValueAsString(view(room, null));
        String unrelatedVillager = current.getPlayers().stream().filter(p -> "VILLAGER".equals(p.getRole()))
                .findFirst().orElseThrow().getPlayerId();
        clearInvocations(push);

        // Complete a deterministic legal decision without invoking a remote model.
        runtime.complete(claimed.getId(), AiTurnDecision.fallback(action));

        assertEquals("SUCCEEDED", jobs.findById(claimed.getId()).orElseThrow().getStatus());
        verify(push).pushPrivate(eq(humanWolf), argThat(event -> refreshFor(event, room.getId())));
        verify(push, never()).pushPrivate(eq(unrelatedVillager), any());
        verify(push, never()).pushStateChange(anyString(), any());
        assertEquals(publicBefore, json.writeValueAsString(view(room, null)));
        List<Map<String, Object>> council = RuleSupport.maps(view(room, humanWolf).getExtra().get("wolfCouncil"));
        assertEquals(2, council.size());
        assertTrue(council.stream().anyMatch(entry -> aiWolf.equals(entry.get("actorId")) && suggestion.equals(entry.get("content"))));
        verifyNoInteractions(client);
    }

    @Test void privateNightTimeoutRefreshesOnlyRecipientsWithoutPublicBroadcast() throws Exception {
        Room room = startHumanRoom();
        GameState current = state(room);
        String timedOutWolf = nightActor(current);
        String villager = current.getPlayers().stream().filter(p -> "VILLAGER".equals(p.getRole()))
                .findFirst().orElseThrow().getPlayerId();
        String publicBefore = json.writeValueAsString(view(room, null));
        String villagerToken = token(view(room, villager));
        int beforeSerial = RuleSupport.number(current.getData().get("phaseSerial"), 0);
        current.setPhaseEndsAt(LocalDateTime.now().minusSeconds(1));
        states.saveAndFlush(current);
        clearInvocations(push);

        runtime.tick(room.getId());

        GameState advanced = state(room);
        assertEquals("NIGHT", advanced.getPhase());
        assertNotEquals(timedOutWolf, nightActor(advanced));
        assertEquals(beforeSerial + 1, RuleSupport.number(advanced.getData().get("phaseSerial"), 0));
        assertEquals(publicBefore, json.writeValueAsString(view(room, null)));
        assertEquals(villagerToken, token(view(room, villager)));
        assertPrivateWindow(view(room, null));
        verify(push, never()).pushStateChange(anyString(), any());
        verify(push).pushPrivate(eq(timedOutWolf), argThat(event -> refreshFor(event, room.getId())));
        verify(push).pushPrivate(eq(nightActor(advanced)), argThat(event -> refreshFor(event, room.getId())));
        verify(push, never()).pushPrivate(eq(villager), any());
        verifyNoInteractions(client);
    }

    private Room startHumanRoom() {
        Room room = new Room(UUID.randomUUID().toString(), "werewolf", "Night privacy integration", RoomStatus.WAITING,
                6, false, null, "text", new LinkedHashMap<>(Map.of("playerCount", 6, "template", "standard", "hasLastWords", "none")));
        List<RoomSeat> seats = new ArrayList<>();
        for (int index = 0; index < 6; index++) seats.add(new RoomSeat(index, UUID.randomUUID().toString(), "玩家" + index,
                false, null, "", true, index == 0));
        room.setSeats(seats);
        room.setHostUserId(seats.get(0).getPlayerId());
        room.syncSeatCount();
        room = rooms.saveAndFlush(room);
        runtime.start("werewolf", room.getId(), user(room.getHostUserId()));
        return room;
    }

    private void submit(Room room, String actor, PlayerAction action) {
        action.setRequestId(UUID.randomUUID().toString());
        action.setExpectedPhaseToken(token(view(room, actor)));
        runtime.action("werewolf", room.getId(), user(actor), action);
    }

    private GameState state(Room room) { return states.findById(room.getId()).orElseThrow(); }
    private GameStateResponse view(Room room, String viewer) { return runtime.state("werewolf", room.getId(), user(viewer == null ? "authenticated-spectator" : viewer)); }
    private String nightActor(GameState state) { return RuleSupport.text(RuleSupport.map(state.getData().get("werewolf")).get("nightActor")); }
    private String token(GameStateResponse response) { return RuleSupport.text(response.getExtra().get("phaseToken")); }
    private List<?> legalActions(GameStateResponse response) { return (List<?>) response.getExtra().get("legalActions"); }
    private boolean refreshFor(PrivateEvent event, String roomId) {
        return "V2_REFRESH".equals(event.type()) && roomId.equals(RuleSupport.map(event.payload()).get("roomId"));
    }
    private void assertPrivateWindow(GameStateResponse response) {
        assertEquals("NIGHT", response.getPhase());
        assertNull(response.getCurrentSeat());
        assertNull(response.getPhaseEndsAt());
        assertTrue(legalActions(response).isEmpty());
        assertFalse(response.getExtra().containsKey("nightActor"));
        assertFalse(response.getExtra().containsKey("nightStep"));
        assertFalse(response.getExtra().containsKey("actionEndsAt"));
    }
    private User user(String id) { User user = new User(); user.setId(id); user.setNickname("隐私验收玩家"); return user; }
}
