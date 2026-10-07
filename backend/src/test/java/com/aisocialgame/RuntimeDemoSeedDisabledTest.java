package com.aisocialgame;

import com.aisocialgame.repository.CommunityPostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = AiSocialGameApplication.class, properties = {
        "app.demo-seed.enabled=true", "spring.datasource.url=jdbc:h2:mem:no-runtime-demo;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class RuntimeDemoSeedDisabledTest {
    @Autowired CommunityPostRepository posts;
    @Test void legacyEnabledFlagCannotGenerateRuntimeDemoRecords() {
        assertFalse(posts.existsById("demo-post-ai-quality"));
        assertFalse(posts.existsById("demo-post-undercover"));
        assertFalse(posts.existsById("demo-post-werewolf"));
    }
}
