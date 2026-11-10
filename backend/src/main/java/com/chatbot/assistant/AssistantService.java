package com.chatbot.assistant;

import com.chatbot.assistant.LlmClient.FunctionCall;
import com.chatbot.assistant.LlmClient.LlmException;
import com.chatbot.assistant.LlmClient.LlmRequest;
import com.chatbot.assistant.LlmClient.LlmResponse;
import com.chatbot.assistant.LlmClient.Usage;
import com.chatbot.catalog.CatalogService;
import com.chatbot.customer.CustomerProfile;
import com.chatbot.customer.CustomerService;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Genera la respuesta de un turno. No envía nada: el envío y su control final son del módulo de conversaciones. */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-AR"));
    private static final String AMOUNTS_CORRECTION = """
            Tu respuesta incluye importes que no devolvió ninguna herramienta en este turno.
            Reescribila usando solo importes devueltos por calcular_presupuesto o buscar_productos, \
            o sin importes si no los tenés.""";

    private final LlmClient llm;
    private final AssistantTools tools;
    private final AssistantRepository repository;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final LlmProperties properties;
    private final JsonMapper jsonMapper;

    AssistantService(
            LlmClient llm,
            AssistantTools tools,
            AssistantRepository repository,
            CustomerService customers,
            CatalogService catalog,
            LlmProperties properties,
            JsonMapper jsonMapper) {
        this.llm = llm;
        this.tools = tools;
        this.repository = repository;
        this.customers = customers;
        this.catalog = catalog;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public Optional<AssistantSettings> settings() {
        return repository.settings();
    }

    public void markDiscarded(UUID runId) {
        repository.markDiscarded(runId);
    }

    public AssistantOutcome respond(AssistantSettings settings, AssistantInput input) {
        long started = System.nanoTime();
        String model = settings.model() != null ? settings.model() : properties.model();
        TurnState state = new TurnState(input);
        List<JsonNode> items = historyItems(input);
        String instructions = instructions(settings, input.customerId());
        Usage usage = Usage.NONE;
        int calls = 0;
        boolean correctionRequested = false;
        // Vueltas con herramientas, más la respuesta final y una corrección posible.
        int maxCalls = settings.maxToolIterations() + 2;
        try {
            while (calls < maxCalls) {
                LlmResponse response = llm.respond(new LlmRequest(model, instructions, items, tools.definitions()));
                calls++;
                usage = usage.plus(response.usage());
                if (!response.functionCalls().isEmpty()) {
                    items.addAll(response.output());
                    for (FunctionCall call : response.functionCalls()) {
                        items.add(functionOutput(call.callId(), tools.execute(call, state)));
                    }
                    continue;
                }
                if (response.text().isBlank()) {
                    return handOffWithFallback(settings, state, model, usage, calls, started, "Respuesta vacía del modelo");
                }
                if (!ReplyValidator.onlyKnownAmounts(response.text(), state.knownAmounts)) {
                    if (!correctionRequested) {
                        correctionRequested = true;
                        items.addAll(response.output());
                        items.add(message("developer", AMOUNTS_CORRECTION));
                        continue;
                    }
                    log.warn("Respuesta descartada por importes no verificados en la conversación {}", input.conversationId());
                    return handOffWithFallback(settings, state, model, usage, calls, started, "Importes no verificados");
                }
                String outcome = state.handoffReason == null ? "REPLIED" : "HANDED_OFF";
                UUID runId = record(input, settings, model, usage, state, calls, started, outcome);
                return new AssistantOutcome.Reply(WhatsAppText.normalize(response.text()), state.handoffReason, runId);
            }
            return handOffWithFallback(settings, state, model, usage, calls, started, "Demasiadas consultas en un turno");
        } catch (LlmException e) {
            log.warn("No se pudo generar la respuesta de la conversación {}: {}", input.conversationId(), e.getMessage());
            UUID runId = record(input, settings, model, usage, state, calls, started, "FAILED");
            return new AssistantOutcome.Failure(e.isRetryable(), e.getMessage(), runId);
        }
    }

    private AssistantOutcome handOffWithFallback(AssistantSettings settings, TurnState state, String model, Usage usage,
            int calls, long started, String reason) {
        UUID runId = record(state.input, settings, model, usage, state, calls, started, "HANDED_OFF");
        return new AssistantOutcome.Reply(settings.fallbackMessage(), reason, runId);
    }

    private UUID record(AssistantInput input, AssistantSettings settings, String model, Usage usage, TurnState state,
            int calls, long started, String outcome) {
        return repository.recordRun(input.conversationId(), input.turnId(), settings.promptVersionId(), model, usage,
                state.toolNames, calls, (System.nanoTime() - started) / 1_000_000, outcome);
    }

    private String instructions(AssistantSettings settings, UUID customerId) {
        CustomerProfile profile = customers.profile(customerId);
        String name = profile.displayName() == null ? "sin nombre conocido" : "\"" + profile.displayName() + "\"";
        return settings.systemPrompt() + "\n\n# Contexto de esta conversación\n"
                + "- Fecha y hora en Argentina: " + ZonedDateTime.now(ARGENTINA).format(DATE_TIME) + "\n"
                + "- Cliente: " + name
                + (profile.locality() == null ? "" : ", de " + profile.locality())
                + (profile.returning() ? ". Ya consultó antes." : ". Es su primera conversación.") + "\n"
                + "- Precios: " + (catalog.pricesAreCurrent()
                        ? "actualizados."
                        : "DESACTUALIZADOS. No informes importes y derivá las cotizaciones.") + "\n";
    }

    private List<JsonNode> historyItems(AssistantInput input) {
        List<JsonNode> items = new ArrayList<>();
        input.history().forEach(item -> items.add(message(item.fromCustomer() ? "user" : "assistant", item.text())));
        return items;
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = jsonMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private ObjectNode functionOutput(String callId, String output) {
        ObjectNode item = jsonMapper.createObjectNode();
        item.put("type", "function_call_output");
        item.put("call_id", callId);
        item.put("output", output);
        return item;
    }
}
