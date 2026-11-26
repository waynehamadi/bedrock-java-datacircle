# Datacircle tools for Amazon Bedrock in the AWS SDK for Java

Find the best GTM APIs. We benchmark all the GTM APIs and we put the results on our website.

Our website: https://datacircle.dev

This library gives Amazon Bedrock's Converse API four tools that call Datacircle's open API, https://api.datacircle.dev/v1. You pass the tools to `converse` or `converseStream` of the [AWS SDK for Java 2.x](https://github.com/aws/aws-sdk-java-v2). Bedrock does not run tools. This library runs each call the model makes to a Datacircle tool. You get the answers to send back to the model. You need no key and no account.

| Tool | What the tool gives | Call |
| --- | --- | --- |
| `datacircle_list_apis` | Every GTM API on Datacircle, with the API's slug, number of plans, number of providers and best plan | `GET /apis` |
| `datacircle_best_plans` | One API's plans, sorted by score out of 100, best first. Each plan comes with a rank and a price per 1,000 calls | `GET /apis/{slug}/best` |
| `datacircle_search_plans` | Plans that match a provider or plan name, on all APIs or on one API. You can sort them by score, provider or price | `GET /plans` |
| `datacircle_provider` | The APIs a provider sells, and the provider's rank on each API | `GET /providers/{slug}` |

## Install

The library is on [JitPack](https://jitpack.io/#waynehamadi/bedrock-java-datacircle). With Maven:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.waynehamadi</groupId>
    <artifactId>bedrock-java-datacircle</artifactId>
    <version>0.1.0</version>
</dependency>
```

With Gradle:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.waynehamadi:bedrock-java-datacircle:0.1.0")
}
```

You need Java 17 or later. The library uses the `software.amazon.awssdk:bedrockruntime` in your app, version 2.25.63 or later. The library adds no other dependency.

## Use the tools with Converse

Put `datacircle.tools()` in the request's `toolConfig`. After each call to the model, add the model's message to your messages. Then add the user message from `datacircle.runToolUses(message)` and call the model again. `runToolUses` gives `null` once the model has no tool use left. The model's last message is the answer.

```java
import dev.datacircle.bedrock.DatacircleTools;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

BedrockRuntimeClient client = BedrockRuntimeClient.create();
DatacircleTools datacircle = new DatacircleTools();

List<Message> messages = new ArrayList<>();
messages.add(Message.builder()
        .role(ConversationRole.USER)
        .content(ContentBlock.fromText("Who are the 3 best providers for the email-finder API?"))
        .build());
while (true) {
    ConverseResponse response = client.converse(request -> request
            .modelId("amazon.nova-pro-v1:0")
            .messages(messages)
            .toolConfig(config -> config.tools(datacircle.tools())));
    Message message = response.output().message();
    messages.add(message);
    Message results = datacircle.runToolUses(message);
    if (results == null) {
        message.content().forEach(block -> {
            if (block.text() != null) {
                System.out.println(block.text());
            }
        });
        break;
    }
    messages.add(results);
}
```

With `BedrockRuntimeAsyncClient`, write the loop above with `client.converse(...).join()` in place of `client.converse(...)`.

## Add your own tools

Put your tools in the same list as the four Datacircle tools. Give `runToolUses` a function that runs your tools. The function gets each tool use that is not a Datacircle tool. The function gives the result as text:

```java
List<Tool> tools = new ArrayList<>(datacircle.tools());
tools.add(Tool.fromToolSpec(spec -> spec
        .name("get_time")
        .description("Gives the time in a city.")
        .inputSchema(ToolInputSchema.fromJson(Document.mapBuilder()
                .putString("type", "object")
                .putDocument("properties", Document.mapBuilder()
                        .putDocument("city", Document.mapBuilder().putString("type", "string").build())
                        .build())
                .build()))));

// In the loop:
Message results = datacircle.runToolUses(message, use -> use.name().equals("get_time")
        ? clock.timeIn(use.input().asMap().get("city").asString())
        : null);
```

Bedrock wants an answer to every tool use of a turn. If your function gives `null`, or you give no function, `runToolUses` answers that no tool has that name. If your function throws an exception, `runToolUses` answers `Error:` and the exception's message.

## Use the tools with ConverseStream

`MessageAccumulator` builds the model's message from the stream's events. The message has the same blocks as a Converse answer: text, reasoning and tool uses.

```java
BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.create();

while (true) {
    MessageAccumulator accumulator = new MessageAccumulator();
    client.converseStream(request -> request
                    .modelId("amazon.nova-pro-v1:0")
                    .messages(messages)
                    .toolConfig(config -> config.tools(datacircle.tools())),
            ConverseStreamResponseHandler.builder().subscriber(accumulator).build()).join();
    Message message = accumulator.message();
    messages.add(message);
    Message results = datacircle.runToolUses(message);
    if (results == null) {
        System.out.println(accumulator.text());
        break;
    }
    messages.add(results);
}
```

To show the text during the stream, write a subscriber that calls `accumulator.accept(event)` for each event.

## Tool answers

Each tool answers in short text: one line per API or plan. Each plan's line links to the plan's page on datacircle.dev. For example, `datacircle_best_plans` with `{"api": "email-finder", "limit": 3}` answers:

```text
Employee email (email-finder): the best 3 of 5,444 plans. Sheet: https://datacircle.dev/email-finder-api-pricing
1. Emelia, 100,000 credits per month: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly. https://datacircle.dev/find-email-apis/emelia/19-monthly
2. ...
```

If a call fails, the tool answers with a line that starts with `Error:`. The error line has the next step. The tool does not throw an exception, so your loop keeps running.

You can call a tool yourself: `datacircle.bestPlans("email-finder", 3)`, `datacircle.listApis("email")`, `datacircle.searchPlans(null, "hunter", "price", 5, null)` and `datacircle.provider("Bright Data")`.

## Options

`new DatacircleTools(baseUrl, timeout)` sets another base URL or another timeout. The defaults are `https://api.datacircle.dev/v1` and 30 seconds.

You can make 60 calls a minute to the API from one IP address. The API's spec is at https://api.datacircle.dev/v1/openapi.json.

## Tests

```bash
./mvnw test                      # offline, on a local server
DATACIRCLE_LIVE=1 ./mvnw test    # also calls each tool on the live API
```

## License

MIT
