package com.aisocialgame;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.GamePlayService;
import com.aisocialgame.service.RoomService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class GamePlayServiceWerewolfTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private GamePlayService gamePlayService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GameStateRepository gameStateRepository;

    @MockBean
    private AiGrpcClient aiGrpcClient;

    private static final String[] PERSONA_IDS = {"ai1", "ai2", "ai3", "ai4"};

    @Test
    void transitionToNightShouldKeepNightOpenWhenHumanWerewolfIsOnline() {
        User host = createLocalUser("werewolf-night-online@example.com", "夜晚真人");
        Room room = roomService.createRoom("werewolf", "夜晚待办房", false, null, "text", Map.of("playerCount", 6), host);
        addAiSeats(room, 5);

        gamePlayService.start("werewolf", room.getId(), host);

        GameState state = gameStateRepository.findById(room.getId()).orElseThrow();
        List<GamePlayerState> players = new ArrayList<>(state.getPlayers());
        GamePlayerState hostState = players.stream().filter(p -> host.getId().equals(p.getPlayerId())).findFirst().orElseThrow();
        List<GamePlayerState> others = players.stream().filter(p -> !host.getId().equals(p.getPlayerId())).toList();

        hostState.setRole("WEREWOLF");
        others.get(0).setRole("WEREWOLF");
        others.get(1).setRole("VILLAGER");
        others.get(2).setRole("SEER");
        others.get(3).setRole("WITCH");
        others.get(4).setRole("HUNTER");
        players.forEach(p -> {
            p.setAlive(true);
            p.setConnectionStatus("ONLINE");
            p.setLastActiveAt(LocalDateTime.now());
            p.setDisconnectedAt(null);
        });

        state.setPlayers(players);
        state.setPhase("DAY_VOTE");
        state.setCurrentSeat(null);
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(30));
        state.getData().put("speakers", players.stream().map(GamePlayerState::getPlayerId).toList());

        String targetId = others.get(1).getPlayerId();
        Map<String, String> votes = new HashMap<>();
        for (GamePlayerState p : others) {
            votes.put(p.getPlayerId(), targetId);
        }
        state.getData().put("votes", votes);
        gameStateRepository.save(state);

        VoteRequest hostVote = new VoteRequest();
        hostVote.setTargetPlayerId(targetId);
        GameStateResponse response = gamePlayService.vote("werewolf", room.getId(), hostVote, host);

        Assertions.assertEquals("NIGHT", response.getPhase());
        Assertions.assertEquals("WEREWOLF", response.getMyRole());
        Assertions.assertNotNull(response.getPendingAction());
        Assertions.assertEquals("WOLF_KILL", response.getPendingAction().getType());
    }

    @Test
    void transitionToNightShouldAutoResolveWhenNoConnectedHumanNightRole() {
        User host = createLocalUser("werewolf-night-ai@example.com", "白天真人");
        Room room = roomService.createRoom("werewolf", "夜晚自动推进房", false, null, "text", Map.of("playerCount", 6), host);
        addAiSeats(room, 5);

        gamePlayService.start("werewolf", room.getId(), host);

        GameState state = gameStateRepository.findById(room.getId()).orElseThrow();
        List<GamePlayerState> players = new ArrayList<>(state.getPlayers());
        GamePlayerState hostState = players.stream().filter(p -> host.getId().equals(p.getPlayerId())).findFirst().orElseThrow();
        List<GamePlayerState> others = players.stream().filter(p -> !host.getId().equals(p.getPlayerId())).toList();

        hostState.setRole("VILLAGER");
        others.get(0).setRole("WEREWOLF");
        others.get(1).setRole("WEREWOLF");
        others.get(2).setRole("SEER");
        others.get(3).setRole("WITCH");
        others.get(4).setRole("HUNTER");
        players.forEach(p -> {
            p.setAlive(true);
            p.setConnectionStatus("ONLINE");
            p.setLastActiveAt(LocalDateTime.now());
            p.setDisconnectedAt(null);
        });

        state.setPlayers(players);
        state.setPhase("DAY_VOTE");
        state.setCurrentSeat(null);
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(30));
        state.getData().put("speakers", players.stream().map(GamePlayerState::getPlayerId).toList());

        String targetId = others.get(4).getPlayerId();
        Map<String, String> votes = new HashMap<>();
        for (GamePlayerState p : others) {
            votes.put(p.getPlayerId(), targetId);
        }
        state.getData().put("votes", votes);
        gameStateRepository.save(state);

        VoteRequest hostVote = new VoteRequest();
        hostVote.setTargetPlayerId(targetId);
        GameStateResponse response = gamePlayService.vote("werewolf", room.getId(), hostVote, host);

        Assertions.assertNotEquals("NIGHT", response.getPhase());
    }

    private User createLocalUser(String email, String nickname) {
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setEmail(email);
        user.setUsername(email.substring(0, email.indexOf("@")));
        user.setPassword("{test}");
        user.setNickname(nickname);
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + nickname);
        user.setLevel(1);
        user.setCoins(0);
        return userRepository.save(user);
    }

    private void addAiSeats(Room room, int count) {
        User host = userRepository.findById(room.getSeats().get(0).getPlayerId()).orElseThrow();
        for (int i = 0; i < count; i++) {
            roomService.addAi(room.getId(), PERSONA_IDS[i % PERSONA_IDS.length], host);
        }
    }
}
