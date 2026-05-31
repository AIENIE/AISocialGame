package com.aisocialgame;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.model.Room;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.GameArchiveRepository;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.GamePlayService;
import com.aisocialgame.service.RoomService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
class TurtleSoupGameEngineTest {
    @Autowired
    private RoomService roomService;

    @Autowired
    private GamePlayService gamePlayService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GameArchiveRepository gameArchiveRepository;

    @MockBean
    private AiGrpcClient aiGrpcClient;

    @Test
    void turtleSoupCanStartAskAndSolveWithoutLeakingSolutionBeforeSettlement() {
        User host = createLocalUser("turtle-host@example.com", "汤友");
        Room room = roomService.createRoom(
                "turtle_soup",
                "海龟汤测试局",
                false,
                null,
                "text",
                Map.of("playerCount", 2, "storyPack", "classic", "difficulty", "easy", "questionLimit", 12),
                host
        );
        roomService.addAi(room.getId(), "ai1", host);

        GameStateResponse started = gamePlayService.start("turtle_soup", room.getId(), host);

        Assertions.assertEquals("QUESTIONING", started.getPhase());
        Assertions.assertEquals("turtle_soup", started.getGameId());
        Assertions.assertNotNull(started.getExtra().get("soupPrompt"));
        Assertions.assertNull(started.getExtra().get("solutionRevealed"));
        Assertions.assertFalse(started.getExtra().containsKey("solution"));

        PlayerAction question = new PlayerAction();
        question.setType("ASK_QUESTION");
        question.setContent("他是在餐厅里喝汤吗？");
        GameStateResponse afterQuestion = gamePlayService.action("turtle_soup", room.getId(), question, host);

        Assertions.assertEquals("QUESTIONING", afterQuestion.getPhase());
        Assertions.assertTrue(((Number) afterQuestion.getExtra().get("questionCount")).intValue() >= 1);
        Assertions.assertFalse(afterQuestion.getExtra().containsKey("solution"));

        PlayerAction guess = new PlayerAction();
        guess.setType("FINAL_GUESS");
        guess.setContent("他发现自己吃的是海龟汤，想起曾经被救命恩人骗了，所以崩溃。");
        GameStateResponse settlement = gamePlayService.action("turtle_soup", room.getId(), guess, host);

        Assertions.assertEquals("SETTLEMENT", settlement.getPhase());
        Assertions.assertEquals("SOLVED", settlement.getWinner());
        Assertions.assertEquals(Boolean.TRUE, settlement.getExtra().get("solutionRevealed"));
        Assertions.assertTrue(settlement.getExtra().containsKey("solution"));
        Assertions.assertTrue(gameArchiveRepository.findAll().stream().anyMatch(archive -> room.getId().equals(archive.getRoomId())));
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
