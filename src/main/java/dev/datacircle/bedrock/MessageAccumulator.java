package dev.datacircle.bedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockStart;

/**
 * Gathers a ConverseStream's events into the model's message, as Converse gives it: its text, its reasoning, with the
 * signature Bedrock wants back beside a tool use, and each tool use, whose input comes in pieces of JSON. Other blocks
 * (citations, images, a system tool's) are left out.
 *
 * <p>It is a {@code Consumer<ConverseStreamOutput>}, so it is the stream's subscriber:
 *
 * <pre>{@code
 * MessageAccumulator accumulator = new MessageAccumulator();
 * client.converseStream(request, ConverseStreamResponseHandler.builder().subscriber(accumulator).build()).join();
 * messages.add(accumulator.message());
 * }</pre>
 *
 * <p>A stream's first event, messageStart, starts it again, so a stream the SDK retries is gathered once.
 */
public final class MessageAccumulator implements Consumer<ConverseStreamOutput> {

    private final SortedMap<Integer, Part> parts = new TreeMap<>();
    private StopReason stopReason;
    private String stopReasonText;

    /**
     * Adds one event of the stream.
     *
     * @param event the event
     */
    @Override
    public synchronized void accept(ConverseStreamOutput event) {
        if (event instanceof MessageStartEvent) {
            parts.clear();
            stopReason = null;
            stopReasonText = null;
        } else if (event instanceof ContentBlockStartEvent start) {
            ToolUseBlockStart use = start.start() == null ? null : start.start().toolUse();
            if (use != null) {
                Part part = part(start.contentBlockIndex());
                part.toolUseId = use.toolUseId();
                part.name = use.name();
                part.type = type(use);
            }
        } else if (event instanceof ContentBlockDeltaEvent deltaEvent && deltaEvent.delta() != null) {
            ContentBlockDelta delta = deltaEvent.delta();
            Part part = part(deltaEvent.contentBlockIndex());
            if (delta.text() != null) {
                part.text().append(delta.text());
            } else if (delta.toolUse() != null) {
                if (delta.toolUse().input() != null) {
                    part.input.append(delta.toolUse().input());
                }
            } else {
                addReasoning(part, delta);
            }
        } else if (event instanceof MessageStopEvent stop) {
            stopReason = stop.stopReason();
            stopReasonText = stop.stopReasonAsString();
        }
    }

    /**
     * The model's message so far, its blocks in their order, to add to the next request's messages.
     *
     * @return the assistant message
     */
    public synchronized Message message() {
        List<ContentBlock> content = new ArrayList<>();
        for (Part part : parts.values()) {
            if (part.toolUseId != null) {
                if (!"server_tool_use".equals(part.type)) {
                    content.add(ContentBlock.fromToolUse(toolUse(part)));
                }
            } else if (part.reasoning != null) {
                content.add(part.reasoning.block());
            } else if (part.text != null && !part.text.toString().isBlank()) {
                content.add(ContentBlock.fromText(part.text.toString()));
            }
        }
        return Message.builder().role(ConversationRole.ASSISTANT).content(content).build();
    }

    /**
     * The message's text so far, its text blocks joined.
     *
     * @return the text, empty when it has none
     */
    public synchronized String text() {
        StringBuilder text = new StringBuilder();
        for (Part part : parts.values()) {
            if (part.toolUseId == null && part.reasoning == null && part.text != null) {
                text.append(part.text);
            }
        }
        return text.toString();
    }

    /**
     * The stream's stop reason, from its messageStop event: {@code TOOL_USE} when the model used tools.
     *
     * @return the stop reason, or null before the stream's end
     */
    public synchronized StopReason stopReason() {
        return stopReason;
    }

    /**
     * The stream's stop reason as Bedrock wrote it, for one this SDK doesn't know.
     *
     * @return the stop reason, or null before the stream's end
     */
    public synchronized String stopReasonAsString() {
        return stopReasonText;
    }

    private Part part(Integer index) {
        return parts.computeIfAbsent(index == null ? 0 : index, i -> new Part());
    }

    private static ToolUseBlock toolUse(Part part) {
        ToolUseBlock.Builder use = ToolUseBlock.builder().toolUseId(part.toolUseId).name(part.name).input(input(part));
        if (part.type != null) {
            try {
                use.type(part.type);
            } catch (LinkageError e) {
                // An SDK before tool use types.
            }
        }
        return use.build();
    }

    /** The tool use's input from its pieces: {} when it has none, or when they are no JSON object. */
    private static Document input(Part part) {
        String raw = part.input.toString();
        if (!raw.isBlank()) {
            try {
                if (Json.parse(raw) instanceof Map<?, ?> map) {
                    return Documents.fromValue(map);
                }
            } catch (IllegalArgumentException e) {
                // Pieces that are no JSON: {} below.
            }
        }
        return Document.fromMap(Map.of());
    }

    /** The tool use's type, like {@code server_tool_use} for a system tool's, on an SDK that has types. */
    private static String type(ToolUseBlockStart use) {
        try {
            return use.typeAsString();
        } catch (LinkageError e) {
            return null;
        }
    }

    private static void addReasoning(Part part, ContentBlockDelta delta) {
        try {
            Reasoning reasoning = part.reasoning != null ? part.reasoning : new Reasoning();
            if (reasoning.add(delta)) {
                part.reasoning = reasoning;
            }
        } catch (LinkageError e) {
            // An SDK before reasoning: its delta is of a kind this SDK doesn't know.
        }
    }

    /** One content block of the message, by its index. */
    private static final class Part {
        private StringBuilder text;
        private String toolUseId;
        private String name;
        private String type;
        private final StringBuilder input = new StringBuilder();
        private Reasoning reasoning;

        private StringBuilder text() {
            if (text == null) {
                text = new StringBuilder();
            }
            return text;
        }
    }
}
