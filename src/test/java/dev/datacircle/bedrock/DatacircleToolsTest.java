package dev.datacircle.bedrock;

import static dev.datacircle.bedrock.FakeDatacircle.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

class DatacircleToolsTest {

    private DatacircleTools tools;

    @BeforeAll
    static void start() throws IOException {
        FakeDatacircle.start();
    }

    @AfterAll
    static void stop() {
        FakeDatacircle.stop();
    }

    @BeforeEach
    void setUp() {
        tools = FakeDatacircle.tools();
    }

    @Test
    void listsApisAndFiltersThemByWords() {
        assertEquals(
                "- Employee email (email-finder): 5,444 plans from 300 providers. Best: Emelia, 100,000 credits per "
                        + "month, score 76.6/100, $4.99 per 1,000.\n"
                        + "- Company profile (linkedin-company): 1 plan from 1 provider.\n"
                        + "2 of 2 APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.",
                tools.listApis(null));
        assertTrue(call("datacircle_list_apis", "{\"query\": \"EMAIL finder\"}").contains("1 of 2 APIs."));
        assertEquals(
                "No API's name or slug holds every word of \"zzz\". Leave the query empty to list 2 APIs.",
                call("datacircle_list_apis", "{\"query\": \" zzz \"}"));
    }

    @Test
    void givesTheBestPlans() {
        assertEquals(
                "Employee email (email-finder): the best 1 of 5,444 plans. "
                        + "Sheet: https://datacircle.dev/email-finder-api-pricing\n"
                        + "1. Emelia, Pro: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly, "
                        + "median 812 ms. https://datacircle.dev/find-email-apis/emelia/pro",
                tools.bestPlans(" Email-Finder ", 3));
        assertEquals(List.of("/v1/apis/email-finder/best?limit=3"), requests);
    }

    @Test
    void searchesThePlans() {
        assertEquals(
                "Found 2 plans; showing 2 to 2.\n"
                        + "- Hunter, Starter (People search): rank 18, not scored, no listed price. "
                        + "https://datacircle.dev/p/hunter",
                tools.searchPlans("people search", "hunter io", "Price", 5, 1));
        assertEquals(List.of("/v1/plans?sort=price&limit=5&offset=1&api=people+search&query=hunter+io"), requests);
    }

    @Test
    void givesAProvidersRanks() {
        assertEquals(
                "Bright Data (https://brightdata.com) sells 2 APIs. Its rank among each API's providers:\n"
                        + "- Employee profile (linkedin-profile): rank 3, 2 plans. Best: Growth, score 71.3/100, "
                        + "$1.05 per 1,000 records.\n"
                        + "- SERP (serp): not ranked, 0 plans.",
                call("datacircle_provider", "{\"provider\": \"Bright Data\"}"));
        assertEquals(List.of("/v1/providers/bright-data"), requests);
    }

    @Test
    void callTakesTheModelsInputWithNullsAndNumbersAsText() {
        assertTrue(call("datacircle_search_plans",
                        "{\"api\": null, \"query\": \"hunter\", \"sort\": null, \"limit\": \"5\", \"offset\": null}")
                .startsWith("Found 2 plans"));
        assertEquals(List.of("/v1/plans?sort=score&limit=5&offset=0&query=hunter"), requests);
        assertTrue(call("datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 2.0}")
                .startsWith("Employee email (email-finder): the best 1"));
        assertTrue(tools.call("datacircle_list_apis", null).orElseThrow().contains("2 of 2 APIs"));
        assertTrue(tools.call("datacircle_list_apis", Document.fromNull()).orElseThrow().contains("2 of 2 APIs"));
        assertEquals(Optional.empty(), tools.call("get_weather", Document.fromMap(Map.of())));
        assertEquals(Optional.empty(), tools.call(null, null));
    }

    @Test
    void saysWhatWentWrong() {
        assertEquals(
                "Error: Datacircle API request failed: HTTP 404. no API has the slug nope. "
                        + "Call datacircle_list_apis for every API's slug.",
                call("datacircle_best_plans", "{\"api\": \"nope\", \"limit\": null}"));
        assertEquals(
                "Error: Datacircle API request failed: HTTP 429. too many calls. The API allows 60 calls a minute "
                        + "from one address. Retry in a minute.",
                call("datacircle_search_plans", "{\"query\": \"busy\"}"));
        assertEquals("Error: The Datacircle API returned invalid JSON.", call("datacircle_best_plans", "{\"api\": \"broken\"}"));
        assertEquals("Error: limit must be between 1 and 50", call("datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 51}"));
        assertEquals("Error: limit must be a whole number", call("datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 2.5}"));
        assertEquals("Error: sort must be one of [price, provider, score]", call("datacircle_search_plans", "{\"sort\": \"x\"}"));
        assertEquals("Error: offset must be 0 or more", call("datacircle_search_plans", "{\"offset\": -1}"));
        assertEquals("Error: api must not be empty", call("datacircle_best_plans", "{\"api\": \" \"}"));
        assertEquals("Error: api must not be empty", tools.bestPlans(null, null));
        assertEquals("Error: provider must not be empty", call("datacircle_provider", "{}"));
        assertEquals("Error: the input of datacircle_provider must be a JSON object.",
                tools.call("datacircle_provider", Document.fromString("Hunter")).orElseThrow());
        assertEquals("Error: query must be a string", call("datacircle_list_apis", "{\"query\": [\"email\"]}"));
        assertTrue(call("datacircle_provider", "{\"provider\": \"北京\"}")
                .startsWith("Error: Datacircle writes each provider's name in Latin letters"));
        assertTrue(requests.stream().noneMatch(r -> r.contains("limit=51")));
    }

    @Test
    void saysWhenTheApiIsUnreachable() {
        DatacircleTools offline = new DatacircleTools("http://127.0.0.1:1/v1", Duration.ofSeconds(2));
        assertEquals(
                "Error: The Datacircle API refused the connection. Check the network and the base URL.",
                offline.listApis(null));
    }

    @Test
    void toolsAreFourSpecsWithObjectSchemas() {
        List<Tool> specs = tools.tools();
        assertEquals(DatacircleTools.NAMES, specs.stream().map(t -> t.toolSpec().name()).toList());
        for (Tool tool : specs) {
            ToolSpecification spec = tool.toolSpec();
            assertTrue(spec.name().matches("[a-zA-Z0-9_-]{1,64}"), spec.name());
            assertFalse(spec.description().isBlank(), spec.name());
            Object schema = Documents.toValue(spec.inputSchema().json());
            assertTrue(schema instanceof Map<?, ?> map && "object".equals(map.get("type")), spec.name());
            assertTrue(((Map<?, ?>) schema).get("properties") instanceof Map, spec.name());
        }
        Map<?, ?> search = (Map<?, ?>) Documents.toValue(specs.get(2).toolSpec().inputSchema().json());
        assertEquals(List.of("api", "query", "sort", "limit", "offset"),
                new ArrayList<>(((Map<?, ?>) search.get("properties")).keySet()));
        Map<?, ?> limit = (Map<?, ?>) ((Map<?, ?>) search.get("properties")).get("limit");
        assertEquals("integer", limit.get("type"));
        assertEquals(new BigDecimal("50"), limit.get("maximum"));
        Map<?, ?> best = (Map<?, ?>) Documents.toValue(specs.get(1).toolSpec().inputSchema().json());
        assertEquals(List.of("api"), best.get("required"));
        Map<?, ?> provider = (Map<?, ?>) Documents.toValue(specs.get(3).toolSpec().inputSchema().json());
        assertEquals(List.of("provider"), provider.get("required"));
        assertTrue(tools.handles("datacircle_provider"));
        assertFalse(tools.handles("get_time"));
        assertFalse(tools.handles(null));
    }

    @Test
    void runToolUsesAnswersEachUseInOrderInOneUserMessage() {
        Message message = Message.builder().role(ConversationRole.ASSISTANT).content(
                ContentBlock.fromText("Looking."),
                use("tooluse_1", "datacircle_best_plans", "{\"api\": \"email-finder\", \"limit\": 3}"),
                use("tooluse_2", "get_time", "{\"city\": \"Paris\"}"),
                use("tooluse_3", "datacircle_provider", "{\"provider\": \"bright-data\"}"),
                use("tooluse_4", "get_weather", "{}"),
                use("tooluse_5", "fails", "{}"),
                use("tooluse_6", "blank", "{}")).build();
        Message results = tools.runToolUses(message, use -> switch (use.name()) {
            case "get_time" -> "12:00 in " + Documents.toValue(use.input()).toString();
            case "fails" -> throw new IllegalStateException("the clock is down");
            case "blank" -> " ";
            default -> null;
        });
        assertEquals(ConversationRole.USER, results.role());
        List<ToolResultBlock> blocks = results.content().stream().map(ContentBlock::toolResult).toList();
        assertEquals(List.of("tooluse_1", "tooluse_2", "tooluse_3", "tooluse_4", "tooluse_5", "tooluse_6"),
                blocks.stream().map(ToolResultBlock::toolUseId).toList());
        List<String> texts = blocks.stream().map(b -> b.content().get(0).text()).toList();
        assertTrue(texts.get(0).startsWith("Employee email (email-finder): the best 1 of 5,444 plans."), texts.get(0));
        assertEquals("12:00 in {city=Paris}", texts.get(1));
        assertTrue(texts.get(2).startsWith("Bright Data (https://brightdata.com) sells 2 APIs."), texts.get(2));
        assertEquals("Error: no tool is named get_weather here.", texts.get(3));
        assertEquals("Error: the clock is down", texts.get(4));
        assertEquals("(empty)", texts.get(5));
        assertTrue(blocks.stream().allMatch(b -> b.status() == null), "no status: only some models take one");
        assertEquals(List.of("/v1/apis/email-finder/best?limit=3", "/v1/providers/bright-data"), requests);
    }

    @Test
    void runToolUsesWithoutOwnToolsAnswersOthersAsUnknown() {
        Message message = Message.builder().role(ConversationRole.ASSISTANT).content(
                use("a", "datacircle_list_apis", "{}"), use("b", "get_time", "{}")).build();
        List<ToolResultBlock> blocks = tools.runToolUses(message).content().stream().map(ContentBlock::toolResult).toList();
        assertTrue(blocks.get(0).content().get(0).text().contains("2 of 2 APIs"));
        assertEquals("Error: no tool is named get_time here.", blocks.get(1).content().get(0).text());
    }

    @Test
    void aMessageWithNoToolUseToAnswerGivesNull() {
        assertNull(tools.runToolUses(Message.builder().role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText("The best is Emelia.")).build()));
        assertNull(tools.runToolUses(null));
        // A system tool's use, which Bedrock answers in the same message, is no use for the app to answer.
        Message grounded = Message.builder().role(ConversationRole.ASSISTANT).content(
                use("server_1", "nova_grounding", "{\"query\": \"x\"}"),
                ContentBlock.fromToolResult(ToolResultBlock.builder().toolUseId("server_1")
                        .content(ToolResultContentBlock.fromText("found")).build()),
                ContentBlock.fromText("Done.")).build();
        assertEquals(List.of(), DatacircleTools.toolUses(grounded));
        assertNull(tools.runToolUses(grounded));
        Message typed = Message.builder().role(ConversationRole.ASSISTANT).content(ContentBlock.fromToolUse(
                ToolUseBlock.builder().toolUseId("server_2").name("nova_grounding").input(Document.fromMap(Map.of()))
                        .type("server_tool_use").build())).build();
        assertNull(tools.runToolUses(typed));
    }

    @Test
    void resultIsEmptyForAnotherTool() {
        assertEquals(Optional.empty(), tools.result(ToolUseBlock.builder().toolUseId("x").name("get_time").build()));
        assertEquals(Optional.empty(), tools.result(null));
        ToolResultBlock result = tools.result(ToolUseBlock.builder().toolUseId("y").name("datacircle_list_apis").build())
                .orElseThrow();
        assertEquals("y", result.toolUseId());
        assertTrue(result.content().get(0).text().contains("2 of 2 APIs"));
    }

    private String call(String name, String input) {
        return tools.call(name, Documents.fromJson(input)).orElseThrow();
    }

    private static ContentBlock use(String id, String name, String input) {
        return ContentBlock.fromToolUse(ToolUseBlock.builder()
                .toolUseId(id)
                .name(name)
                .input(Documents.fromJson(input))
                .build());
    }
}
