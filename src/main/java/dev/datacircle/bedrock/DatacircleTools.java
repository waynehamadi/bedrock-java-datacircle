package dev.datacircle.bedrock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

/**
 * Tools for Amazon Bedrock's Converse API on the open <a href="https://datacircle.dev">Datacircle</a> API, which
 * benchmarks the GTM APIs (email finders, company enrichment, LinkedIn data and more) and ranks every plan of each by
 * its score out of 100 and its price per 1,000.
 *
 * <p>{@link #tools()} gives the four tool specs for a {@code ConverseRequest}'s or {@code ConverseStreamRequest}'s
 * {@code toolConfig}, beside your own. Bedrock does not run tools: {@link #runToolUses(Message, Function)} runs each
 * tool use of the model's message and gives the user message of toolResult blocks that answers them. The API needs no
 * key and no account. It allows 60 calls a minute from one address. Its spec is at
 * {@code https://api.datacircle.dev/v1/openapi.json}.
 *
 * <pre>{@code
 * DatacircleTools datacircle = new DatacircleTools();
 * ConverseResponse response = client.converse(request -> request
 *         .modelId("amazon.nova-pro-v1:0")
 *         .messages(messages)
 *         .toolConfig(config -> config.tools(datacircle.tools())));
 * Message results = datacircle.runToolUses(response.output().message());
 * }</pre>
 */
public final class DatacircleTools {

    /** The four tools' names, as the model calls them. */
    public static final List<String> NAMES = List.of(
            "datacircle_list_apis", "datacircle_best_plans", "datacircle_search_plans", "datacircle_provider");

    private static final List<Tool> TOOLS = List.of(
            tool("datacircle_list_apis", "List the GTM APIs that Datacircle ranks (email finder, company enrichment, "
                    + "LinkedIn profile and more), each with its slug, how many plans and providers it has, and its best "
                    + "plan.", """
                    {"type": "object", "properties": {
                      "query": {"type": "string", "description": "Words that must all appear in the API's name or slug, like email or linkedin. Leave it out to list every API"}}}
                    """),
            tool("datacircle_best_plans", "Get the best plans of one GTM API, best first by Datacircle's score out of "
                    + "100, with each plan's price per 1,000 and its page.", """
                    {"type": "object", "properties": {
                      "api": {"type": "string", "description": "The API's slug, as datacircle_list_apis gives it, like email-finder"},
                      "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "Number of plans from 1 through 50. 10 when left out"}},
                     "required": ["api"]}
                    """),
            tool("datacircle_search_plans", "Search the plans of the GTM APIs on Datacircle by API, and by words in "
                    + "the provider's or plan's name, sorted by score (best first), provider (A to Z) or price (lowest $ "
                    + "per 1,000 first).", """
                    {"type": "object", "properties": {
                      "api": {"type": "string", "description": "The API's name or slug, like email-finder. A provider's name goes in query. Every API when left out"},
                      "query": {"type": "string", "description": "Words that must all appear in the provider's or plan's name, like hunter. Every plan when left out"},
                      "sort": {"type": "string", "enum": ["score", "provider", "price"], "description": "Sort: score, provider or price. score when left out"},
                      "limit": {"type": "integer", "minimum": 1, "maximum": 50, "description": "Number of plans from 1 through 50. 10 when left out"},
                      "offset": {"type": "integer", "minimum": 0, "description": "Number of found plans to skip first. 0 when left out"}}}
                    """),
            tool("datacircle_provider", "Get one provider's rank on each GTM API it sells on Datacircle, with its best "
                    + "plan there. A provider is a company that sells the APIs, like Hunter or Bright Data.", """
                    {"type": "object", "properties": {
                      "provider": {"type": "string", "description": "The provider's name or slug, like Bright Data or bright-data"}},
                     "required": ["provider"]}
                    """));

    private static final int MAX_TEXT_LENGTH = 120;
    private static final int MAX_PROVIDER_APIS = 100;
    private static final String NEW_LINE = "\n";

    private final DatacircleClient client;

    /** Tools on {@code https://api.datacircle.dev/v1}, with a 30 s timeout. */
    public DatacircleTools() {
        this(DatacircleClient.DEFAULT_BASE_URL, Duration.ofSeconds(30));
    }

    /**
     * Tools on another address of the API, or with another timeout.
     *
     * @param baseUrl the API's base URL, {@code https://api.datacircle.dev/v1} when null
     * @param timeout the connection and answer timeout
     */
    public DatacircleTools(String baseUrl, Duration timeout) {
        this.client = new DatacircleClient(baseUrl, timeout);
    }

    /**
     * The four tools, for a request's {@code toolConfig(config -> config.tools(...))}. Your own tools go beside them in
     * one list.
     *
     * @return the four tool specs, in the order of {@link #NAMES}, a list you can't change
     */
    public List<Tool> tools() {
        return TOOLS;
    }

    /**
     * Says whether a tool's name is one of the four.
     *
     * @param name the tool's name
     * @return true for one of {@link #NAMES}
     */
    public boolean handles(String name) {
        return name != null && NAMES.contains(name);
    }

    /**
     * The tool uses of the model's message that the app answers, in order: each toolUse block, but one the message
     * answers itself with a toolResult block (a system tool's, which Bedrock runs).
     *
     * @param message the model's message
     * @return its tool uses, empty when it has none
     */
    public static List<ToolUseBlock> toolUses(Message message) {
        List<ToolUseBlock> uses = new ArrayList<>();
        if (message == null || !message.hasContent()) {
            return uses;
        }
        Set<String> answered = new HashSet<>();
        for (ContentBlock block : message.content()) {
            if (block.toolResult() != null) {
                answered.add(block.toolResult().toolUseId());
            }
        }
        for (ContentBlock block : message.content()) {
            ToolUseBlock use = block.toolUse();
            if (use != null && !answered.contains(use.toolUseId()) && !isServerToolUse(use)) {
                uses.add(use);
            }
        }
        return uses;
    }

    /**
     * Answers each tool use of the model's message, in order, in one user message of toolResult blocks, for the next
     * request's messages. Bedrock wants every tool use of a turn answered in the next message, so a use of a tool that
     * is not one of the four is answered with an error the model reads.
     *
     * @param message the model's message, {@code response.output().message()}
     * @return the user message that answers its tool uses, or {@code null} when it has none: the model's answer
     */
    public Message runToolUses(Message message) {
        return runToolUses(message, null);
    }

    /**
     * Answers each tool use of the model's message, in order, in one user message of toolResult blocks, for the next
     * request's messages: the four tools' uses by {@link #result(ToolUseBlock)}, the others by {@code own}.
     *
     * @param message the model's message, {@code response.output().message()}
     * @param own runs a use of one of your own tools and gives its result as text, often JSON. Its null, or a null
     *     {@code own}, answers that no tool has that name. What it throws is answered as an error the model reads.
     * @return the user message that answers its tool uses, or {@code null} when it has none: the model's answer
     */
    public Message runToolUses(Message message, Function<ToolUseBlock, String> own) {
        List<ToolUseBlock> uses = toolUses(message);
        if (uses.isEmpty()) {
            return null;
        }
        List<ContentBlock> content = new ArrayList<>();
        for (ToolUseBlock use : uses) {
            ToolResultBlock result = result(use).orElseGet(() -> textResult(use.toolUseId(), ownResult(use, own)));
            content.add(ContentBlock.fromToolResult(result));
        }
        return Message.builder().role(ConversationRole.USER).content(content).build();
    }

    /**
     * Runs one tool use of the model's message, when it calls one of the four tools.
     *
     * @param use the tool use
     * @return the toolResult that answers it, its text the tool's answer or a line that starts with {@code Error:},
     *     or empty when the use is of another tool
     */
    public Optional<ToolResultBlock> result(ToolUseBlock use) {
        if (use == null) {
            return Optional.empty();
        }
        return call(use.name(), use.input()).map(text -> textResult(use.toolUseId(), text));
    }

    /**
     * Calls one of the four tools by its name on the model's input.
     *
     * @param name the tool's name
     * @param input the tool use's input, a JSON object; null is {@code {}}
     * @return the tool's answer, or empty when the name is none of the four tools
     */
    public Optional<String> call(String name, Document input) {
        if (!handles(name)) {
            return Optional.empty();
        }
        Object parsed = Documents.toValue(input);
        if (parsed == null) {
            parsed = Map.of();
        }
        if (!(parsed instanceof Map<?, ?> args)) {
            return Optional.of("Error: the input of " + name + " must be a JSON object.");
        }
        try {
            return Optional.of(switch (name) {
                case "datacircle_list_apis" -> listApis(string(args, "query"));
                case "datacircle_best_plans" -> bestPlans(string(args, "api"), integer(args, "limit"));
                case "datacircle_search_plans" -> searchPlans(string(args, "api"), string(args, "query"),
                        string(args, "sort"), integer(args, "limit"), integer(args, "offset"));
                default -> provider(string(args, "provider"));
            });
        } catch (IllegalArgumentException e) {
            return Optional.of(formatError(e));
        }
    }

    /**
     * {@code datacircle_list_apis}: the GTM APIs Datacircle ranks, each with its slug, its counts and its best plan.
     *
     * @param query words that must all appear in the API's name or slug, or null for every API
     * @return the APIs, one a line, or a line that starts with {@code Error:}
     */
    public String listApis(String query) {
        try {
            return formatApis(client.listApis(), query);
        } catch (RuntimeException e) {
            return formatError(e);
        }
    }

    /**
     * {@code datacircle_best_plans}: one API's best plans, best first by score.
     *
     * @param api the API's slug, like {@code email-finder}
     * @param limit how many plans, 1 to 50, or null for 10
     * @return the ranked plans, one a line, or a line that starts with {@code Error:}
     */
    public String bestPlans(String api, Integer limit) {
        try {
            return formatBestPlans(client.bestPlans(api, limit));
        } catch (RuntimeException e) {
            return formatError(e) + (isStatus(e, 404) ? " Call datacircle_list_apis for every API's slug." : "");
        }
    }

    /**
     * {@code datacircle_search_plans}: the plans of every API, or of one, by provider or plan name.
     *
     * @param api the API's name or slug, or null for every API
     * @param query words that must all appear in the provider's or plan's name, or null for every plan
     * @param sort {@code score}, {@code provider} or {@code price}, or null for score
     * @param limit how many plans, 1 to 50, or null for 10
     * @param offset how many found plans to skip first, or null for 0
     * @return the plans found, one a line, or a line that starts with {@code Error:}
     */
    public String searchPlans(String api, String query, String sort, Integer limit, Integer offset) {
        try {
            return formatSearch(client.searchPlans(api, query, sort, limit, offset), offset == null ? 0 : offset);
        } catch (RuntimeException e) {
            boolean unknownApi = api != null && !api.isBlank() && (isStatus(e, 400) || isStatus(e, 404));
            return formatError(e) + (unknownApi ? " Call datacircle_list_apis for every API's name and slug." : "");
        }
    }

    /**
     * {@code datacircle_provider}: one provider's rank on each API it sells.
     *
     * @param provider the provider's name or slug, like {@code Bright Data} or {@code bright-data}
     * @return the provider's ranks, one API a line, or a line that starts with {@code Error:}
     */
    public String provider(String provider) {
        try {
            return formatProvider(client.provider(provider));
        } catch (RuntimeException e) {
            return formatError(e);
        }
    }

    private static Tool tool(String name, String description, String schema) {
        return Tool.fromToolSpec(ToolSpecification.builder()
                .name(name)
                .description(description)
                .inputSchema(ToolInputSchema.fromJson(Documents.fromJson(schema)))
                .build());
    }

    /** A system tool's use, which Bedrock runs: its type is {@code server_tool_use}, on an SDK that has types. */
    private static boolean isServerToolUse(ToolUseBlock use) {
        try {
            return "server_tool_use".equals(use.typeAsString());
        } catch (LinkageError e) {
            return false;
        }
    }

    private static String ownResult(ToolUseBlock use, Function<ToolUseBlock, String> own) {
        String text;
        try {
            text = own == null ? null : own.apply(use);
        } catch (RuntimeException e) {
            return formatError(e);
        }
        return text != null ? text : "Error: no tool is named " + use.name() + " here.";
    }

    /** A toolResult of one text block. A blank text is sent as {@code (empty)}: Bedrock refuses a blank one. */
    private static ToolResultBlock textResult(String toolUseId, String text) {
        return ToolResultBlock.builder()
                .toolUseId(toolUseId)
                .content(ToolResultContentBlock.fromText(text == null || text.isBlank() ? "(empty)" : text))
                .build();
    }

    private static String string(Map<?, ?> args, String name) {
        Object value = args.get(name);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        throw new IllegalArgumentException(name + " must be a string");
    }

    private static Integer integer(Map<?, ?> args, String name) {
        Object value = args.get(name);
        if (value == null || value instanceof String text && text.isBlank()) {
            return null;
        }
        try {
            BigDecimal number = value instanceof BigDecimal n ? n : new BigDecimal(((String) value).trim());
            return number.intValueExact();
        } catch (ArithmeticException | ClassCastException | NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    private static String formatApis(Object apis, String query) {
        if (!(apis instanceof List<?> list)) {
            throw unexpected("the list of APIs is not a list");
        }
        if (list.isEmpty()) {
            return "The Datacircle API returned no APIs.";
        }
        List<String> words = words(query);
        List<String> lines = new ArrayList<>();
        for (Object api : list) {
            String name = required(api, "api");
            String slug = required(api, "slug");
            if (!matchesAll(name + " " + slug, words)) {
                continue;
            }
            StringBuilder line = new StringBuilder("- ")
                    .append(truncate(name))
                    .append(" (")
                    .append(slug)
                    .append("): ")
                    .append(count(field(api, "plans"), "plan"))
                    .append(" from ")
                    .append(count(field(api, "providers"), "provider"))
                    .append('.');
            Object best = field(api, "best");
            if (best instanceof Map) {
                line.append(" Best: ")
                        .append(truncate(required(best, "provider")))
                        .append(", ")
                        .append(truncate(required(best, "plan")))
                        .append(", ")
                        .append(score(field(best, "score")))
                        .append(", ")
                        .append(price(field(best, "perThousand"), null))
                        .append('.');
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            return "No API's name or slug holds every word of \"" + query.trim() + "\". Leave the query empty to list "
                    + list.size() + " APIs.";
        }
        return String.join(NEW_LINE, lines) + NEW_LINE + lines.size() + " of " + list.size()
                + " APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.";
    }

    private static String formatBestPlans(Object response) {
        List<?> plans = list(response, "plans");
        StringBuilder result = new StringBuilder()
                .append(required(response, "api"))
                .append(" (")
                .append(required(response, "slug"))
                .append("): ");
        if (plans.isEmpty()) {
            return result.append("no plans found.").toString();
        }
        result.append("the best ")
                .append(plans.size())
                .append(" of ")
                .append(count(field(response, "total"), "plan"))
                .append('.');
        appendIfPresent(result, "Sheet", text(response, "sheetPage"));
        for (Object plan : plans) {
            result.append(NEW_LINE).append(formatPlan(plan, false));
        }
        return result.toString();
    }

    private static String formatSearch(Object response, int requestedOffset) {
        List<?> plans = list(response, "plans");
        Object total = field(response, "total");
        int offset = field(response, "offset") instanceof BigDecimal n ? n.intValue() : requestedOffset;
        if (plans.isEmpty()) {
            if (total instanceof BigDecimal n && n.signum() > 0) {
                return "Found " + count(total, "plan") + ", but the offset " + offset
                        + " is past the last one. Use an offset below " + n.toPlainString() + ".";
            }
            return "No plans found.";
        }
        StringBuilder result = new StringBuilder("Found ")
                .append(count(total, "plan"))
                .append("; showing ")
                .append(offset + 1)
                .append(" to ")
                .append(offset + plans.size())
                .append('.');
        appendIfPresent(result, "Sheet", text(response, "sheetPage"));
        for (Object plan : plans) {
            result.append(NEW_LINE).append(formatPlan(plan, true));
        }
        return result.toString();
    }

    private static String formatProvider(Object response) {
        List<?> apis = list(response, "apis");
        StringBuilder result = new StringBuilder(required(response, "provider"));
        String website = text(response, "website");
        if (!website.isEmpty()) {
            result.append(" (").append(website).append(')');
        }
        if (apis.isEmpty()) {
            return result.append(" sells no API on Datacircle.").toString();
        }
        result.append(" sells ").append(plural(apis.size(), "API")).append(". Its rank among each API's providers:");
        int shown = 0;
        for (Object api : apis) {
            if (shown == MAX_PROVIDER_APIS) {
                result.append(NEW_LINE)
                        .append("And ")
                        .append(plural(apis.size() - shown, "more API"))
                        .append('.');
                break;
            }
            shown++;
            Object rank = field(api, "rank");
            List<?> plans = list(api, "plans");
            result.append(NEW_LINE)
                    .append("- ")
                    .append(truncate(required(api, "api")))
                    .append(" (")
                    .append(required(api, "slug"))
                    .append("): ")
                    .append(rank instanceof BigDecimal n ? "rank " + n.intValue() : "not ranked")
                    .append(", ")
                    .append(plural(plans.size(), "plan"))
                    .append('.');
            Object best = bestScored(plans);
            if (best != null) {
                result.append(" Best: ")
                        .append(truncate(required(best, "plan")))
                        .append(", ")
                        .append(score(field(best, "score")))
                        .append(", ")
                        .append(price(field(best, "perThousand"), field(best, "per")))
                        .append('.');
            }
        }
        return result.toString();
    }

    private static Object bestScored(List<?> plans) {
        Object best = null;
        BigDecimal bestScore = null;
        for (Object plan : plans) {
            if (field(plan, "score") instanceof BigDecimal score && (bestScore == null || score.compareTo(bestScore) > 0)) {
                best = plan;
                bestScore = score;
            }
        }
        return best;
    }

    private static String formatPlan(Object plan, boolean withApi) {
        StringBuilder line = new StringBuilder();
        Object rank = field(plan, "rank");
        line.append(!withApi && rank instanceof BigDecimal n ? n.intValue() + ". " : "- ")
                .append(truncate(required(plan, "provider")))
                .append(", ")
                .append(truncate(required(plan, "plan")));
        if (withApi) {
            line.append(" (").append(truncate(required(plan, "api"))).append(')');
        }
        line.append(": ");
        if (withApi && rank instanceof BigDecimal n) {
            line.append("rank ").append(n.intValue()).append(", ");
        }
        line.append(score(field(plan, "score")))
                .append(", ")
                .append(price(field(plan, "perThousand"), field(plan, "per")));
        if (field(plan, "notFoundPerThousand") instanceof BigDecimal notFound) {
            line.append(", ").append(dollars(notFound)).append(" per 1,000 not found");
        }
        String billing = text(plan, "billing");
        if (!billing.isEmpty()) {
            line.append(", ").append(billing);
        }
        if (field(plan, "latencyMs") instanceof BigDecimal latency) {
            line.append(", median ").append(latency.longValue()).append(" ms");
        }
        line.append('.');
        String page = text(plan, "page");
        if (!page.isEmpty()) {
            line.append(' ').append(page);
        }
        return line.toString();
    }

    private static String score(Object score) {
        return score instanceof BigDecimal n
                ? "score " + n.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "/100"
                : "not scored";
    }

    private static String price(Object perThousand, Object per) {
        if (!(perThousand instanceof BigDecimal amount)) {
            return "no listed price";
        }
        String unit = per instanceof String word && !word.isBlank() ? " " + word + "s" : "";
        return dollars(amount) + " per 1,000" + unit;
    }

    private static String dollars(BigDecimal amount) {
        return "$" + amount.stripTrailingZeros().toPlainString();
    }

    private static String count(Object number, String word) {
        return number instanceof BigDecimal n ? plural(n.longValue(), word) : "? " + word + "s";
    }

    private static String plural(long count, String word) {
        return String.format(Locale.ROOT, "%,d", count) + " " + word + (count == 1 ? "" : "s");
    }

    private static List<String> words(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return List.of(fold(query).trim().split("[\\s-]+"));
    }

    private static boolean matchesAll(String text, List<String> words) {
        String folded = fold(text);
        return words.stream().allMatch(folded::contains);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static void appendIfPresent(StringBuilder result, String label, String value) {
        if (!value.isEmpty()) {
            result.append(' ').append(label).append(": ").append(value);
        }
    }

    private static Object field(Object node, String name) {
        return node instanceof Map<?, ?> map ? map.get(name) : null;
    }

    private static String required(Object node, String name) {
        if (!(field(node, name) instanceof String value) || value.isBlank()) {
            throw unexpected(name + " is missing");
        }
        return value;
    }

    private static List<?> list(Object node, String name) {
        if (!(field(node, name) instanceof List<?> value)) {
            throw unexpected(name + " is not a list");
        }
        return value;
    }

    private static String text(Object node, String name) {
        return field(node, name) instanceof String value && !value.isBlank() ? value : "";
    }

    private static DatacircleException unexpected(String what) {
        return new DatacircleException("The Datacircle API returned an answer of an unexpected shape: " + what
                + ". Retry later, or read https://api.datacircle.dev/v1/openapi.json.");
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH - 3) + "...";
    }

    private static boolean isStatus(RuntimeException error, int statusCode) {
        return error instanceof DatacircleException e && e.statusCode() == statusCode;
    }

    private static String formatError(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "Error: the Datacircle API request failed." : "Error: " + message;
    }
}
