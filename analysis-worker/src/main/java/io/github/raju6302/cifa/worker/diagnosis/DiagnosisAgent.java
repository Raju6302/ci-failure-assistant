package io.github.raju6302.cifa.worker.diagnosis;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Diagnoses one failed job with Claude, using the MCP tools to investigate.
 *
 * <p>This is a manual tool-use loop rather than the SDK's tool runner because we need
 * control the runner doesn't give: a step limit, a token budget that ends with a request
 * to submit, cost accounting per call, and tools that come from an MCP server at runtime.
 * The conversation is append-only (each response goes back unchanged via
 * {@code toParam()}), which keeps the prompt cache and the model's thinking valid.
 */
public class DiagnosisAgent {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisAgent.class);
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final int MAX_LOGGED_ARGUMENT_CHARS = 300;

    private final AnthropicClient client;
    private final AgentTools tools;
    private final DiagnosisProperties properties;
    private final Clock clock;

    public DiagnosisAgent(AnthropicClient client, AgentTools tools, DiagnosisProperties properties, Clock clock) {
        this.client = client;
        this.tools = tools;
        this.properties = properties;
        this.clock = clock;
    }

    public DiagnosisResult diagnose(FailureContext context) {
        long started = clock.millis();
        List<BetaTool> toolDefinitions = toolDefinitions();
        List<BetaMessageParam> messages = new ArrayList<>();
        messages.add(BetaMessageParam.ofUser(Prompts.failure(context)));
        List<DiagnosisResult.ToolCall> toolCalls = new ArrayList<>();
        Usage usage = Usage.NONE;
        boolean budgetWarningSent = false;

        for (int step = 1; step <= properties.maxSteps(); step++) {
            BetaMessage response = send(toolDefinitions, messages);
            usage = usage.plus(response.usage().inputTokens(), response.usage().outputTokens(),
                    response.usage().cacheReadInputTokens().orElse(0L),
                    response.usage().cacheCreationInputTokens().orElse(0L), properties.pricing());

            BetaStopReason stopReason = response.stopReason().orElse(BetaStopReason.END_TURN);
            if (BetaStopReason.REFUSAL.equals(stopReason)) {
                // Check before reading content: a refusal may come with empty or partial content.
                throw AgentException.permanent("The model declined to diagnose this job");
            }
            messages.add(response.toParam());

            List<BetaToolUseBlock> calls = response.content().stream().flatMap(block -> block.toolUse().stream()).toList();
            if (calls.isEmpty()) {
                if (BetaStopReason.MAX_TOKENS.equals(stopReason)) {
                    throw AgentException.permanent("Response hit max_tokens before calling a tool");
                }
                messages.add(BetaMessageParam.ofUser(Prompts.SUBMIT_REMINDER));
                continue;
            }

            List<BetaContentBlockParam> results = new ArrayList<>();
            for (BetaToolUseBlock call : calls) {
                if (SubmitDiagnosisTool.NAME.equals(call.name())) {
                    Diagnosis diagnosis = parseSubmission(call, results);
                    if (diagnosis != null) {
                        long latency = clock.millis() - started;
                        log.info("Diagnosed job {} in {} steps, {} tokens, ${}", context.failedJobId(), step,
                                usage.totalTokens(), String.format("%.4f", usage.costUsd()));
                        return new DiagnosisResult(diagnosis, "AGENT", properties.model(), step, toolCalls,
                                usage.withLatency(latency), null);
                    }
                    continue;
                }
                AgentTools.Result result = runTool(call);
                toolCalls.add(new DiagnosisResult.ToolCall(call.name(), shorten(call._input().toString()),
                        result.isError(), result.text().length()));
                results.add(toolResult(call.id(), result.text(), result.isError()));
            }
            if (!budgetWarningSent && usage.totalTokens() >= properties.tokenBudget()) {
                results.add(BetaContentBlockParam.ofText(Prompts.BUDGET_REACHED));
                budgetWarningSent = true;
            }
            // All results of one turn go back in a single user message.
            messages.add(BetaMessageParam.ofUserOfContentBlockParams(results));
        }
        throw AgentException.permanent("No diagnosis after " + properties.maxSteps() + " steps");
    }

    /** Returns null (and queues an error result) if the arguments are unusable, so the model can retry. */
    private static Diagnosis parseSubmission(BetaToolUseBlock call, List<BetaContentBlockParam> results) {
        try {
            return SubmitDiagnosisTool.parse(call._input());
        } catch (SubmitDiagnosisTool.InvalidDiagnosisException e) {
            results.add(toolResult(call.id(), "Invalid diagnosis: " + e.getMessage() + ". Call submit_diagnosis again.", true));
            return null;
        }
    }

    private AgentTools.Result runTool(BetaToolUseBlock call) {
        try {
            Map<String, Object> arguments = call._input().convert(new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            return tools.call(call.name(), arguments == null ? Map.of() : arguments);
        } catch (RuntimeException e) {
            log.warn("Tool {} failed: {}", call.name(), e.getMessage());
            return new AgentTools.Result("Tool failed: " + e.getMessage(), true);
        }
    }

    private BetaMessage send(List<BetaTool> toolDefinitions, List<BetaMessageParam> messages) {
        MessageCreateParams.Builder request = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokensPerTurn())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(properties.effort())).build())
                .systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder()
                        .text(Prompts.SYSTEM)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build()))
                .messages(messages);
        toolDefinitions.forEach(request::addTool);
        if (properties.serverSideFallback()) {
            // On a safety refusal, the API re-runs the request on Anthropic's recommended fallback model.
            request.addBeta(FALLBACK_BETA).putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        try {
            return client.beta().messages().create(request.build());
        } catch (RateLimitException | InternalServerException | AnthropicIoException e) {
            throw new AgentException("Claude API temporarily unavailable: " + e.getMessage(), true, e);
        } catch (NoCredentialsException | UnauthorizedException e) {
            throw new AgentException("Claude API credentials are missing or invalid (set ANTHROPIC_API_KEY)", false, e);
        } catch (AnthropicServiceException e) {
            // 529 overloaded is temporary; other 4xx (bad request, auth, permissions) are not.
            throw new AgentException("Claude API error " + e.statusCode() + ": " + e.getMessage(),
                    e.statusCode() == 529, e);
        }
    }

    private List<BetaTool> toolDefinitions() {
        List<BetaTool> definitions = new ArrayList<>();
        for (AgentTools.Spec spec : tools.list()) {
            definitions.add(toBetaTool(spec));
        }
        definitions.add(SubmitDiagnosisTool.definition());
        return definitions;
    }

    @SuppressWarnings("unchecked")
    static BetaTool toBetaTool(AgentTools.Spec spec) {
        Map<String, Object> schema = spec.inputSchema() == null ? Map.of() : spec.inputSchema();
        BetaTool.InputSchema.Properties.Builder properties = BetaTool.InputSchema.Properties.builder();
        Object declared = schema.get("properties");
        if (declared instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach((name, property) -> properties.putAdditionalProperty(name, JsonValue.from(property)));
        }
        BetaTool.InputSchema.Builder inputSchema = BetaTool.InputSchema.builder().properties(properties.build());
        if (schema.get("required") instanceof List<?> required) {
            inputSchema.required(required.stream().map(String::valueOf).toList());
        }
        return BetaTool.builder()
                .name(spec.name())
                .description(spec.description() == null ? "" : spec.description())
                .inputSchema(inputSchema.build())
                .build();
    }

    private static BetaContentBlockParam toolResult(String toolUseId, String text, boolean isError) {
        return BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
                .toolUseId(toolUseId)
                .content(text)
                .isError(isError)
                .build());
    }

    private static String shorten(String text) {
        return text.length() <= MAX_LOGGED_ARGUMENT_CHARS ? text : text.substring(0, MAX_LOGGED_ARGUMENT_CHARS) + "…";
    }
}
