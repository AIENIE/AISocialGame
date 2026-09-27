package com.aisocialgame.service.ai.v2;
import com.aisocialgame.AiSocialGameApplication;
import com.aisocialgame.integration.grpc.client.AiGrpcClient;
import com.aisocialgame.integration.grpc.dto.AiChatResult;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same isolated application wiring as collection, with RPC replaced before context creation. */
@SpringBootTest(classes=AiSocialGameApplication.class,properties={"spring.datasource.url=jdbc:h2:mem:conversation-wiring;DB_CLOSE_DELAY=-1;MODE=MySQL","app.game.scheduler-enabled=false","app.ai.validation-call-limit=0"})
@ActiveProfiles("test")
class ConversationValidationWiringTest {
 @MockitoBean AiGrpcClient client;
 @Autowired AiTurnGenerator generator;
 @TempDir Path temp;
 @Test void allNinetySixTraverseActualGeneratorAndRulesWithMockRpc()throws Exception {
  var frozen=ConversationValidation.freeze("SYNTHETIC_TEST");
  ConversationValidation.Generate generate=s->{
   var a=s.adapter().fallback(s.observation());
   if(s.id().startsWith("sequence:")&&s.id().endsWith(":4")&&Set.of("SPEAK","DISCUSS").contains(a.getType()))a.setContent("目前这些结果还不足以排除另一种解释，我继续观察。");
   String body=ConversationValidation.JSON.writeValueAsString(Map.of("action",a,"evidenceEventIds",s.adapter().conversation(s.observation()).replyTo().stream().map(AiConversationContext.ReplyReference::eventId).toList()));
   when(client.chatCompletions(anyString(),anyLong(),anyString(),anyString(),anyList(),anyString(),anyInt())).thenReturn(new AiChatResult(body,"mock-only",10,10));
   var d=generator.generate(s.adapter(),s.observation(),"mock-"+UUID.randomUUID());assertFalse(d.fallback(),s.id()+":"+d.diagnostics());return d;
  };
  var pilot=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("pilot.json"),Map.of(),"PILOT",Set.of(),generate);pilot.run();
  var remaining=new ConversationValidation(frozen.manifest(),frozen.bundle(),temp.resolve("remaining.json"),ConversationValidation.read(pilot.output),"REMAINING",Set.of(),generate);remaining.run();
  assertEquals(96,remaining.rows.values().stream().filter(r->"GENERATED".equals(r.get("status"))).count());
  verify(client,times(96)).chatCompletions(anyString(),anyLong(),anyString(),anyString(),anyList(),anyString(),anyInt());
 }
}
