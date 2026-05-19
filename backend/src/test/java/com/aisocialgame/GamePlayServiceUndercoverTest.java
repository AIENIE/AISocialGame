package com.aisocialgame;

import com.aisocialgame.dto.GamePlayerView;
import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.SpeakRequest;
import com.aisocialgame.dto.VoteRequest;
import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.GameState;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
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
class GamePlayServiceUndercoverTest {

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

    @Test
    void aiSpeaksOneByOnePerPoll() {
        var host = createLocalUser("undercover-host@example.com", "房主");
        Room room = roomService.createRoom("undercover", "顺序发言房", false, null, "voice", Map.of("playerCount", 4), host);
        roomService.addAi(room.getId(), "ai1", host);
        roomService.addAi(room.getId(), "ai2", host);
        roomService.addAi(room.getId(), "ai3", host);

        GameStateResponse start = gamePlayService.start("undercover", room.getId(), host);
        List<String> aiIds = start.getPlayers().stream().filter(GamePlayerView::isAi).map(GamePlayerView::getPlayerId).toList();

        SpeakRequest speak = new SpeakRequest();
        speak.setContent("到我发言了");
        gamePlayService.speak("undercover", room.getId(), speak, host);

        GameState midState = gameStateRepository.findById(room.getId()).orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> speakers = (List<String>) midState.getData().get("speakers");
        Assertions.assertEquals(2, speakers.size(), "只应当出现一位 AI 自动发言");
        Assertions.assertTrue(speakers.contains(host.getId()));
        long aiSpokenCount = speakers.stream().filter(aiIds::contains).count();
        Assertions.assertEquals(1, aiSpokenCount, "应只有一名 AI 发言");
        Assertions.assertEquals("DESCRIPTION", midState.getPhase());

        int alive = (int) midState.getPlayers().stream().filter(GamePlayerState::isAlive).count();
        int remaining = alive - speakers.size();
        GameStateResponse latest = null;
        for (int i = 0; i < remaining; i++) {
            latest = gamePlayService.state("undercover", room.getId(), host);
            if (i < remaining - 1) {
                Assertions.assertEquals("DESCRIPTION", latest.getPhase(), "尚有 AI 未发言时不应跳到投票");
            }
        }
        Assertions.assertNotNull(latest);
        Assertions.assertEquals("VOTING", latest.getPhase(), "全部发言完成后应进入投票");
    }

    @Test
    void voteResultAppearsAfterAllPlayersSubmit() {
        var host = createLocalUser("undercover-host2@example.com", "主持人");
        var guest = createLocalUser("undercover-guest2@example.com", "玩家1");
        Room room = roomService.createRoom("undercover", "投票等待房", false, null, "voice", Map.of("playerCount", 4), host);
        roomService.joinRoom(room.getId(), guest.getNickname(), guest, null);
        roomService.addAi(room.getId(), "ai1", host);
        roomService.addAi(room.getId(), "ai2", host);

        gamePlayService.start("undercover", room.getId(), host);

        GameState state = gameStateRepository.findById(room.getId()).orElseThrow();
        List<String> aliveIds = state.getPlayers().stream().map(GamePlayerState::getPlayerId).toList();
        state.setPhase("VOTING");
        state.setCurrentSeat(null);
        state.getData().put("speakers", new ArrayList<>(aliveIds));
        state.getData().put("votes", new HashMap<String, String>());
        state.setPhaseEndsAt(LocalDateTime.now().plusSeconds(30));
        gameStateRepository.save(state);

        GameStateResponse voting = gamePlayService.state("undercover", room.getId(), host);
        Assertions.assertEquals("VOTING", voting.getPhase());
        Assertions.assertEquals(aliveIds.size(), voting.getPlayers().stream().filter(GamePlayerView::isAlive).count());

        String aiTarget = voting.getPlayers().stream().filter(GamePlayerView::isAi).findFirst().map(GamePlayerView::getPlayerId).orElseThrow();

        VoteRequest hostVote = new VoteRequest();
        hostVote.setTargetPlayerId(aiTarget);
        GameStateResponse afterHostVote = gamePlayService.vote("undercover", room.getId(), hostVote, host);
        GameState stateAfterHost = gameStateRepository.findById(room.getId()).orElseThrow();
        Assertions.assertEquals("VOTING", stateAfterHost.getPhase(), "尚有玩家未投票，不能直接公布结果");
        Assertions.assertTrue(stateAfterHost.getPlayers().stream().allMatch(GamePlayerState::isAlive));

        VoteRequest guestVote = new VoteRequest();
        guestVote.setTargetPlayerId(aiTarget);
        GameStateResponse afterGuestVote = gamePlayService.vote("undercover", room.getId(), guestVote, guest);
        Assertions.assertNotEquals("VOTING", afterGuestVote.getPhase(), "所有人投票后应进入下一阶段或结算");
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
}
