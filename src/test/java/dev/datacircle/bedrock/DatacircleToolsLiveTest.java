package dev.datacircle.bedrock;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

/** Each tool once on the live API, by its name and the model's input, and all four by runToolUses. */
@EnabledIfEnvironmentVariable(named = "DATACIRCLE_LIVE", matches = "1")
class DatacircleToolsLiveTest {

    private final DatacircleTools tools = new DatacircleTools();

    private String call(String name, String input) {
        return tools.call(name, Documents.fromJson(input)).orElseThrow();
    }

    @Test
    void listsTheApis() {
        String answer = call("datacircle_list_apis", "{\"query\": \"email\"}");
        System.out.println(answer);
        assertTrue(answer.contains("(email-finder): "), answer);
        assertTrue(answer.contains(" APIs. Pass a slug to datacircle_best_plans"), answer);
    }

    @Test
    void givesTheBestPlans() {
        String answer = call("datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 3}");
        System.out.println(answer);
        assertTrue(answer.startsWith("Employee email (email-finder): the best 3 of "), answer);
        assertTrue(answer.contains("\n1. ") && answer.contains("\n3. "), answer);
    }

    @Test
    void searchesThePlans() {
        String answer = call("datacircle_search_plans",
                "{\"api\": null, \"query\": \"hunter\", \"sort\": \"price\", \"limit\": 3, \"offset\": null}");
        System.out.println(answer);
        assertTrue(answer.startsWith("Found ") && answer.contains("; showing 1 to 3."), answer);
        assertTrue(answer.contains("- Hunter, "), answer);
    }

    @Test
    void givesAProvidersRanks() {
        String answer = call("datacircle_provider", "{\"provider\": \"Hunter\"}");
        System.out.println(answer);
        assertTrue(answer.startsWith("Hunter (https://hunter.io) sells "), answer);
        assertTrue(answer.contains(": rank "), answer);
    }

    @Test
    void runToolUsesCallsEachOnTheLiveApi() {
        Message message = Message.builder().role(ConversationRole.ASSISTANT).content(
                use("1", "datacircle_list_apis", "{}"),
                use("2", "datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 2}"),
                use("3", "datacircle_search_plans", "{\"query\": \"hunter\"}"),
                use("4", "datacircle_provider", "{\"provider\": \"Bright Data\"}")).build();
        List<String> texts = tools.runToolUses(message).content().stream()
                .map(block -> block.toolResult().content().get(0).text()).toList();
        texts.forEach(text -> System.out.println(text.lines().findFirst().orElse("")));
        assertTrue(texts.get(0).contains("(email-finder): "), texts.get(0));
        assertTrue(texts.get(1).startsWith("Employee email (email-finder): the best 2 of "), texts.get(1));
        assertTrue(texts.get(2).startsWith("Found "), texts.get(2));
        assertTrue(texts.get(3).startsWith("Bright Data (https://brightdata.com) sells "), texts.get(3));
    }

    private static ContentBlock use(String id, String name, String input) {
        return ContentBlock.fromToolUse(ToolUseBlock.builder().toolUseId(id).name(name)
                .input(Documents.fromJson(input)).build());
    }
}
