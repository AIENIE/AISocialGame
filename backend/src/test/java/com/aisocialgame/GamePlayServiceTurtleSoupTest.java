package com.aisocialgame;

import com.aisocialgame.dto.GameStateResponse;
import com.aisocialgame.dto.PlayerAction;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.model.GameStatus;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.GameArchiveRepository;
import com.aisocialgame.repository.GameEventRepository;
import com.aisocialgame.repository.GameStateRepository;
import com.aisocialgame.repository.UserRepository;
import com.aisocialgame.service.GamePlayService;
import com.aisocialgame.service.GameService;
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
class GamePlayServiceTurtleSoupTest {

    @Autowired
    private GameService gameService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GamePlayService gamePlayService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GameStateRepository gameStateRepository;

    @Autowired
    private GameArchiveRepository gameArchiveRepository;

    @Autowired
    private GameEventRepository gameEventRepository;

    @MockBean
    private AiGrpcClient aiGrpcClient;

    @Test
    void metadataShouldExposeActiveEngineBackedTurtleSoup() {
        var game = gameService.findById("turtle_soup").orElseThrow();

        Assertions.assertEquals(GameStatus.ACTIVE, game.getStatus());
        Assertions.assertTrue(game.isEngineBacked());
        Assertions.assertEquals(1, game.getMinPlayers());
        Assertions.assertTrue(game.getPhaseDefinitions().stream().anyMatch(phase -> "QUESTIONING".equals(phase.phase())));
    }

    @Test
    void turtleSoupShouldAskQuestionsRevealCluesAndArchiveSolvedGame() {
        User host = createLocalUser("turtle-host@example.com", "解谜玩家");
        var room = roomService.createRoom("turtle_soup", "海龟汤验收房", false, null, "text", Map.of("playerCount", 2, "caseId", "midnight_train", "maxQuestions", 8, "aiAssist", false), host);
        roomService.addAi(room.getId(), "ai1", host);

        GameStateResponse start = gamePlayService.start("turtle_soup", room.getId(), host);
        Assertions.assertEquals("QUESTIONING", start.getPhase());
        Assertions.assertEquals("TURTLE_SOUP_PLAYER", start.getMyRole());
        Assertions.assertNull(start.getExtra().get("solution"), "结算前不应向玩家暴露汤底");
        Assertions.assertTrue(String.valueOf(start.getExtra().get("surface")).contains("末班车"));

        PlayerAction question = new PlayerAction();
        question.setType("ASK_QUESTION");
        question.setContent("司机是否看到了红色围巾？");
        GameStateResponse afterQuestion = gamePlayService.action("turtle_soup", room.getId(), question, host);

        Assertions.assertEquals("QUESTIONING", afterQuestion.getPhase());
        Assertions.assertEquals(1, afterQuestion.getExtra().get("questionCount"));
        Assertions.assertTrue(String.valueOf(afterQuestion.getExtra().get("knownClues")).contains("红色围巾"));
        Assertions.assertTrue(String.valueOf(afterQuestion.getExtra().get("qaHistory")).contains("是"));

        PlayerAction solution = new PlayerAction();
        solution.setType("SUBMIT_SOLUTION");
        solution.setContent("汤底是乘客早已死亡，司机看到的是车窗反光和红色围巾，所以误以为她还在车上。");
        GameStateResponse settlement = gamePlayService.action("turtle_soup", room.getId(), solution, host);

        Assertions.assertEquals("SETTLEMENT", settlement.getPhase());
        Assertions.assertEquals("SOLVED", settlement.getWinner());
        Assertions.assertTrue(String.valueOf(settlement.getExtra().get("solution")).contains("车窗反光"));
        Assertions.assertTrue(String.valueOf(settlement.getExtra().get("hostVerdict")).contains("成功"));

        var persisted = gameStateRepository.findById(room.getId()).orElseThrow();
        String archiveId = String.valueOf(persisted.getData().get("archiveId"));
        Assertions.assertTrue(gameArchiveRepository.existsById(archiveId));
        Assertions.assertTrue(gameEventRepository.findByArchiveIdOrderBySeqAsc(archiveId).stream()
                .anyMatch(event -> "TURTLE_SOUP_SOLVED".equals(event.getEventType())));
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
