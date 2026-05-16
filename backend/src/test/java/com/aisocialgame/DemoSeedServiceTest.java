package com.aisocialgame;

import com.aisocialgame.repository.AiDecisionTraceRepository;
import com.aisocialgame.repository.AiPersonaMemoryRepository;
import com.aisocialgame.repository.CommunityPostRepository;
import com.aisocialgame.repository.PlayerStatsRepository;
import com.aisocialgame.repository.RoomRepository;
import com.aisocialgame.repository.credit.CreditRedeemCodeRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = AiSocialGameApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.demo-seed.enabled=true")
class DemoSeedServiceTest {

    @Autowired
    private CommunityPostRepository communityPostRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private PlayerStatsRepository playerStatsRepository;

    @Autowired
    private CreditRedeemCodeRepository creditRedeemCodeRepository;

    @Autowired
    private AiPersonaMemoryRepository aiPersonaMemoryRepository;

    @Autowired
    private AiDecisionTraceRepository aiDecisionTraceRepository;

    @Test
    void shouldSeedLocalDemoDataWhenEnabled() {
        Assertions.assertTrue(communityPostRepository.existsById("demo-post-ai-quality"));
        Assertions.assertTrue(roomRepository.existsById("demo-undercover-room"));
        Assertions.assertTrue(roomRepository.existsById("demo-werewolf-room"));
        Assertions.assertTrue(playerStatsRepository.existsById("demo-rank-luna-total:total"));
        Assertions.assertTrue(creditRedeemCodeRepository.findByCode("DEMO-LOCAL-1000").isPresent());
        Assertions.assertTrue(aiPersonaMemoryRepository.findByPersonaIdAndGameIdAndRoleKey("ai1", "undercover", "UNDERCOVER").isPresent());
        Assertions.assertTrue(aiDecisionTraceRepository.existsByRoomIdAndActionAndPersonaId("demo-ai-quality-room", "SPEAK", "ai1"));
    }
}
