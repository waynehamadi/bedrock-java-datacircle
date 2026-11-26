package dev.datacircle.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

class MessageAccumulatorTest {

    @Test
    void gathersTextAndToolUsesFromTheirPieces() {
        MessageAccumulator accumulator = new MessageAccumulator();
        accumulator.accept(start());
        accumulator.accept(delta(0, ContentBlockDelta.fromText("Call")));
        accumulator.accept(delta(0, ContentBlockDelta.fromText("ing.")));
        accumulator.accept(stop(0));
        accumulator.accept(toolStart(1, "tooluse_a", "datacircle_best_plans"));
        accumulator.accept(delta(1, ContentBlockDelta.fromToolUse(u -> u.input("{\"api\": \"em"))));
        accumulator.accept(delta(1, ContentBlockDelta.fromToolUse(u -> u.input("ail-finder\", \"limit\": 3}"))));
        accumulator.accept(stop(1));
        accumulator.accept(toolStart(2, "tooluse_b", "datacircle_list_apis"));
        accumulator.accept(delta(2, ContentBlockDelta.fromToolUse(u -> u.input(""))));
        accumulator.accept(stop(2));
        accumulator.accept(toolStart(3, "tooluse_c", "get_time"));
        accumulator.accept(stop(3));
        accumulator.accept(ConverseStreamOutput.messageStopBuilder().stopReason(StopReason.TOOL_USE).build());
        accumulator.accept(ConverseStreamOutput.metadataBuilder().build());

        Message message = accumulator.message();
        assertEquals(ConversationRole.ASSISTANT, message.role());
        assertEquals(4, message.content().size());
        assertEquals("Calling.", message.content().get(0).text());
        ToolUseBlock best = message.content().get(1).toolUse();
        assertEquals("tooluse_a", best.toolUseId());
        assertEquals("datacircle_best_plans", best.name());
        assertEquals(Documents.fromJson("{\"api\": \"email-finder\", \"limit\": 3}"), best.input());
        assertEquals(Map.of(), Documents.toValue(message.content().get(2).toolUse().input()));
        assertEquals(Map.of(), Documents.toValue(message.content().get(3).toolUse().input()));
        assertEquals("Calling.", accumulator.text());
        assertEquals(StopReason.TOOL_USE, accumulator.stopReason());
        assertEquals("tool_use", accumulator.stopReasonAsString());
        assertEquals(List.of("datacircle_best_plans", "datacircle_list_apis", "get_time"),
                DatacircleTools.toolUses(message).stream().map(ToolUseBlock::name).toList());
    }

    @Test
    void keepsTheReasoningAndItsSignature() {
        MessageAccumulator accumulator = new MessageAccumulator();
        accumulator.accept(start());
        accumulator.accept(delta(0, ContentBlockDelta.fromReasoningContent(ReasoningContentBlockDelta.fromText("The user "))));
        accumulator.accept(delta(0, ContentBlockDelta.fromReasoningContent(ReasoningContentBlockDelta.fromText("wants plans."))));
        accumulator.accept(delta(0, ContentBlockDelta.fromReasoningContent(ReasoningContentBlockDelta.fromSignature("sig=="))));
        accumulator.accept(delta(1, ContentBlockDelta.fromReasoningContent(
                ReasoningContentBlockDelta.fromRedactedContent(SdkBytes.fromUtf8String("hidden")))));
        accumulator.accept(toolStart(2, "tooluse_a", "datacircle_list_apis"));
        accumulator.accept(delta(2, ContentBlockDelta.fromToolUse(u -> u.input("{}"))));

        List<ContentBlock> content = accumulator.message().content();
        assertEquals(3, content.size());
        assertEquals("The user wants plans.", content.get(0).reasoningContent().reasoningText().text());
        assertEquals("sig==", content.get(0).reasoningContent().reasoningText().signature());
        assertEquals("hidden", content.get(1).reasoningContent().redactedContent().asUtf8String());
        assertEquals("datacircle_list_apis", content.get(2).toolUse().name());
        assertEquals("", accumulator.text());
        assertNull(accumulator.stopReason());
    }

    @Test
    void leavesOutBlankTextSystemToolsAndBlocksItDoesNotKnow() {
        MessageAccumulator accumulator = new MessageAccumulator();
        accumulator.accept(start());
        accumulator.accept(delta(0, ContentBlockDelta.fromText("\n")));
        accumulator.accept(ConverseStreamOutput.contentBlockStartBuilder().contentBlockIndex(1)
                .start(ContentBlockStart.fromToolUse(u -> u.toolUseId("server_1").name("nova_grounding")
                        .type("server_tool_use")))
                .build());
        accumulator.accept(delta(1, ContentBlockDelta.fromToolUse(u -> u.input("{\"query\": \"x\"}"))));
        accumulator.accept(delta(2, ContentBlockDelta.fromCitation(c -> c.title("A page"))));
        accumulator.accept(delta(3, ContentBlockDelta.fromText("Done.")));
        assertEquals(List.of("Done."), accumulator.message().content().stream().map(ContentBlock::text).toList());
    }

    @Test
    void aBrokenInputIsAnEmptyObject() {
        MessageAccumulator accumulator = new MessageAccumulator();
        accumulator.accept(toolStart(0, "a", "datacircle_provider"));
        accumulator.accept(delta(0, ContentBlockDelta.fromToolUse(u -> u.input("{\"provider\": "))));
        accumulator.accept(toolStart(1, "b", "datacircle_provider"));
        accumulator.accept(delta(1, ContentBlockDelta.fromToolUse(u -> u.input("[1]"))));
        List<ContentBlock> content = accumulator.message().content();
        assertEquals(Map.of(), Documents.toValue(content.get(0).toolUse().input()));
        assertEquals(Map.of(), Documents.toValue(content.get(1).toolUse().input()));
    }

    @Test
    void aNewMessageStartBeginsAgain() {
        MessageAccumulator accumulator = new MessageAccumulator();
        accumulator.accept(start());
        accumulator.accept(delta(0, ContentBlockDelta.fromText("Half a")));
        accumulator.accept(start());
        accumulator.accept(delta(0, ContentBlockDelta.fromText("Whole.")));
        accumulator.accept(ConverseStreamOutput.messageStopBuilder().stopReason(StopReason.END_TURN).build());
        assertEquals("Whole.", accumulator.text());
        assertEquals(1, accumulator.message().content().size());
        assertEquals(StopReason.END_TURN, accumulator.stopReason());
    }

    private static ConverseStreamOutput start() {
        return ConverseStreamOutput.messageStartBuilder().role(ConversationRole.ASSISTANT).build();
    }

    private static ConverseStreamOutput toolStart(int index, String id, String name) {
        return ConverseStreamOutput.contentBlockStartBuilder()
                .contentBlockIndex(index)
                .start(ContentBlockStart.fromToolUse(u -> u.toolUseId(id).name(name)))
                .build();
    }

    private static ConverseStreamOutput delta(int index, ContentBlockDelta delta) {
        return ConverseStreamOutput.contentBlockDeltaBuilder().contentBlockIndex(index).delta(delta).build();
    }

    private static ConverseStreamOutput stop(int index) {
        return ConverseStreamOutput.contentBlockStopBuilder().contentBlockIndex(index).build();
    }
}
