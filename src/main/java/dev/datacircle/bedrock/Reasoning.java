package dev.datacircle.bedrock;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;

/**
 * A reasoning block of a stream, from its pieces. Its own class, as an SDK before 2.30.27 has no reasoning: the
 * accumulator calls it in a {@code try} that catches the {@link LinkageError} such an SDK throws.
 */
final class Reasoning {

    private final StringBuilder text = new StringBuilder();
    private String signature;
    private SdkBytes redactedContent;

    /** Adds a delta's piece of reasoning: false when the delta has none. */
    boolean add(ContentBlockDelta delta) {
        ReasoningContentBlockDelta reasoning = delta.reasoningContent();
        if (reasoning == null) {
            return false;
        }
        if (reasoning.text() != null) {
            text.append(reasoning.text());
        }
        if (reasoning.signature() != null) {
            signature = reasoning.signature();
        }
        if (reasoning.redactedContent() != null) {
            redactedContent = reasoning.redactedContent();
        }
        return true;
    }

    /** The block, as Converse gives it: the reasoning's text and signature, or its redacted content. */
    ContentBlock block() {
        if (redactedContent != null && text.length() == 0) {
            return ContentBlock.fromReasoningContent(ReasoningContentBlock.fromRedactedContent(redactedContent));
        }
        return ContentBlock.fromReasoningContent(ReasoningContentBlock.fromReasoningText(
                ReasoningTextBlock.builder().text(text.toString()).signature(signature).build()));
    }
}
