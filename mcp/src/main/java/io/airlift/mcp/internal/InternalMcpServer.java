package io.airlift.mcp.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.mcp.McpMetadata;
import io.airlift.mcp.McpRequestContext;
import io.airlift.mcp.McpServer;
import io.airlift.mcp.handler.MessageWriter;
import io.airlift.mcp.handler.PromptEntry;
import io.airlift.mcp.handler.PromptHandler;
import io.airlift.mcp.handler.ResourceEntry;
import io.airlift.mcp.handler.ResourceHandler;
import io.airlift.mcp.handler.ResourceTemplateEntry;
import io.airlift.mcp.handler.ResourceTemplateHandler;
import io.airlift.mcp.handler.ToolEntry;
import io.airlift.mcp.handler.ToolHandler;
import io.airlift.mcp.model.CallToolRequest;
import io.airlift.mcp.model.CallToolResult;
import io.airlift.mcp.model.GetPromptRequest;
import io.airlift.mcp.model.GetPromptResult;
import io.airlift.mcp.model.InitializeRequest;
import io.airlift.mcp.model.InitializeResult;
import io.airlift.mcp.model.InitializeResult.LoggingCapabilities;
import io.airlift.mcp.model.InitializeResult.ServerCapabilities;
import io.airlift.mcp.model.ListChanged;
import io.airlift.mcp.model.ListPromptsResult;
import io.airlift.mcp.model.ListResourceTemplatesResult;
import io.airlift.mcp.model.ListResourcesResult;
import io.airlift.mcp.model.ListToolsResult;
import io.airlift.mcp.model.Meta;
import io.airlift.mcp.model.Prompt;
import io.airlift.mcp.model.ReadResourceRequest;
import io.airlift.mcp.model.ReadResourceResult;
import io.airlift.mcp.model.Resource;
import io.airlift.mcp.model.ResourceContents;
import io.airlift.mcp.model.ResourceTemplate;
import io.airlift.mcp.model.ResourceTemplateValues;
import io.airlift.mcp.model.SetLevelRequest;
import io.airlift.mcp.model.SubscribeListChanged;
import io.airlift.mcp.model.Tool;
import io.airlift.mcp.sessions.SessionController;
import io.airlift.mcp.sessions.SessionId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.glassfish.jersey.uri.UriTemplate;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.mcp.McpException.exception;
import static io.airlift.mcp.internal.InternalRequestContext.requireSessionId;
import static io.airlift.mcp.model.Constants.HEADER_SESSION_ID;
import static io.airlift.mcp.model.Constants.PROTOCOL_MCP_2025_06_18;
import static io.airlift.mcp.model.JsonRpcErrorCode.INVALID_PARAMS;
import static io.airlift.mcp.model.JsonRpcErrorCode.INVALID_REQUEST;
import static io.airlift.mcp.model.JsonRpcErrorCode.RESOURCE_NOT_FOUND;
import static io.airlift.mcp.model.LoggingLevel.INFO;
import static io.airlift.mcp.sessions.SessionKey.LOGGING_LEVEL;
import static java.util.Objects.requireNonNull;

public class InternalMcpServer
        implements McpServer
{
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = ImmutableList.of(PROTOCOL_MCP_2025_06_18);

    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<>();
    private final Map<String, PromptEntry> prompts = new ConcurrentHashMap<>();
    private final Map<URI, ResourceEntry> resources = new ConcurrentHashMap<>();
    private final Map<UriTemplate, ResourceTemplateEntry> resourceTemplates = new ConcurrentHashMap<>();
    private final Optional<SessionController> sessionController;
    private final ObjectMapper objectMapper;
    private final McpMetadata metadata;
    private final TaskEmulationDecorator taskEmulationDecorator;
    private final LifeCycleManager lifeCycleManager;

    @Inject
    public InternalMcpServer(
            Optional<SessionController> sessionController,
            ObjectMapper objectMapper,
            McpMetadata metadata,
            TaskEmulationDecorator taskEmulationDecorator,
            LifeCycleManager lifeCycleManager,
            Set<ToolEntry> tools,
            Set<PromptEntry> prompts,
            Set<ResourceEntry> resources,
            Set<ResourceTemplateEntry> resourceTemplates)
    {
        this.sessionController = requireNonNull(sessionController, "sessionController is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.taskEmulationDecorator = requireNonNull(taskEmulationDecorator, "taskEmulationDecorator is null");
        this.lifeCycleManager = requireNonNull(lifeCycleManager, "lifeCycleManager is null");

        tools.forEach(tool -> addTool(tool.tool(), tool.toolHandler()));
        prompts.forEach(prompt -> addPrompt(prompt.prompt(), prompt.promptHandler()));
        resources.forEach(resource -> addResource(resource.resource(), resource.handler()));
        resourceTemplates.forEach(resourceTemplate -> addResourceTemplate(resourceTemplate.resourceTemplate(), resourceTemplate.handler()));
    }

    @Override
    public void stop()
    {
        lifeCycleManager.stop();
    }

    @Override
    public void addTool(Tool tool, ToolHandler toolHandler)
    {
        ToolHandler decoratedToolHandler = taskEmulationDecorator.decorateTool(toolHandler);
        tools.put(tool.name(), new ToolEntry(tool, decoratedToolHandler));
    }

    @Override
    public void removeTool(String toolName)
    {
        tools.remove(toolName);
    }

    @Override
    public void addPrompt(Prompt prompt, PromptHandler promptHandler)
    {
        prompts.put(prompt.name(), new PromptEntry(prompt, promptHandler));
    }

    @Override
    public void removePrompt(String promptName)
    {
        prompts.remove(promptName);
    }

    @Override
    public void addResource(Resource resource, ResourceHandler handler)
    {
        resources.put(toUri(resource.uri()), new ResourceEntry(resource, handler));
    }

    @Override
    public void removeResource(String resourceUri)
    {
        resources.remove(toUri(resourceUri));
    }

    @Override
    public void addResourceTemplate(ResourceTemplate resourceTemplate, ResourceTemplateHandler handler)
    {
        resourceTemplates.put(toUriTemplate(resourceTemplate.uriTemplate()), new ResourceTemplateEntry(resourceTemplate, handler));
    }

    @Override
    public void removeResourceTemplate(String uriTemplate)
    {
        resourceTemplates.remove(toUriTemplate(uriTemplate));
    }

    public InitializeResult initialize(HttpServletRequest request, HttpServletResponse response, InitializeRequest initializeRequest)
    {
        sessionController.ifPresent(controller -> {
            SessionId sessionId = controller.createSession(request);
            response.addHeader(HEADER_SESSION_ID, sessionId.id());
            controller.setSessionValue(sessionId, LOGGING_LEVEL, INFO);
        });

        boolean protocolVersionIsSupported = SUPPORTED_PROTOCOL_VERSIONS.contains(initializeRequest.protocolVersion());
        String protocolVersion = protocolVersionIsSupported ? initializeRequest.protocolVersion() : SUPPORTED_PROTOCOL_VERSIONS.getLast();

        boolean sessionsEnabled = sessionController.isPresent();

        ServerCapabilities serverCapabilities = new ServerCapabilities(
                Optional.empty(),
                sessionsEnabled ? Optional.of(new LoggingCapabilities()) : Optional.empty(),
                metadata.prompts() ? Optional.of(new ListChanged(false)) : Optional.empty(),
                metadata.resources() ? Optional.of(new SubscribeListChanged(false, false)) : Optional.empty(),
                metadata.tools() ? Optional.of(new ListChanged(false)) : Optional.empty());

        return new InitializeResult(protocolVersion, serverCapabilities, metadata.implementation(), metadata.instructions());
    }

    public ListToolsResult listTools()
    {
        List<Tool> localTools = tools.values().stream()
                .map(ToolEntry::tool)
                .collect(toImmutableList());
        return new ListToolsResult(localTools);
    }

    public CallToolResult callTool(HttpServletRequest request, MessageWriter messageWriter, CallToolRequest callToolRequest)
    {
        ToolEntry toolEntry = tools.get(callToolRequest.name());
        if (toolEntry == null) {
            throw exception(INVALID_PARAMS, "Tool not found: " + callToolRequest.name());
        }

        McpRequestContext requestContext = new InternalRequestContext(objectMapper, sessionController, request, messageWriter, progressToken(callToolRequest));
        return toolEntry.toolHandler().callTool(requestContext, callToolRequest);
    }

    public ListPromptsResult listPrompts()
    {
        List<Prompt> localPrompts = prompts.values().stream()
                .map(PromptEntry::prompt)
                .collect(toImmutableList());
        return new ListPromptsResult(localPrompts);
    }

    public GetPromptResult getPrompt(HttpServletRequest request, MessageWriter messageWriter, GetPromptRequest getPromptRequest)
    {
        PromptEntry promptEntry = prompts.get(getPromptRequest.name());
        if (promptEntry == null) {
            throw exception(INVALID_PARAMS, "Prompt not found: " + getPromptRequest.name());
        }

        McpRequestContext requestContext = new InternalRequestContext(objectMapper, sessionController, request, messageWriter, progressToken(getPromptRequest));
        return promptEntry.promptHandler().getPrompt(requestContext, getPromptRequest);
    }

    private Optional<Object> progressToken(Meta meta)
    {
        return meta.meta().flatMap(m -> Optional.ofNullable(m.get("progressToken")));
    }

    public ListResourcesResult listResources()
    {
        List<Resource> localResources = resources.values().stream()
                .map(ResourceEntry::resource)
                .collect(toImmutableList());
        return new ListResourcesResult(localResources);
    }

    public ListResourceTemplatesResult listResourceTemplates()
    {
        List<ResourceTemplate> localResourceTemplates = resourceTemplates.values().stream()
                .map(ResourceTemplateEntry::resourceTemplate)
                .collect(toImmutableList());
        return new ListResourceTemplatesResult(localResourceTemplates);
    }

    public ReadResourceResult readResources(HttpServletRequest request, MessageWriter messageWriter, ReadResourceRequest readResourceRequest)
    {
        McpRequestContext requestContext = new InternalRequestContext(objectMapper, sessionController, request, messageWriter, progressToken(readResourceRequest));

        List<ResourceContents> resourceContents = findResource(readResourceRequest.uri())
                .map(resourceEntry -> resourceEntry.handler().readResource(requestContext, resourceEntry.resource(), readResourceRequest))
                .or(() -> findResourceTemplate(readResourceRequest.uri()).map(match -> match.entry.handler().readResourceTemplate(requestContext, match.entry.resourceTemplate(), readResourceRequest, match.values)))
                .orElseThrow(() -> exception(RESOURCE_NOT_FOUND, "Resource not found: " + readResourceRequest.uri()));

        return new ReadResourceResult(resourceContents);
    }

    private Optional<ResourceEntry> findResource(String uriString)
    {
        URI uri = toUri(uriString);

        return resources.entrySet()
                .stream()
                .filter(entry -> entry.getKey().equals(uri))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static URI toUri(String uriString)
    {
        try {
            return URI.create(uriString);
        }
        catch (IllegalArgumentException e) {
            throw exception(INVALID_REQUEST, "Invalid URI: " + uriString);
        }
    }

    private static UriTemplate toUriTemplate(String uriString)
    {
        try {
            return new UriTemplate(uriString);
        }
        catch (IllegalArgumentException e) {
            throw exception(INVALID_REQUEST, "Invalid URI: " + uriString);
        }
    }

    public Object setLoggingLevel(HttpServletRequest request, SetLevelRequest setLevelRequest)
    {
        sessionController.ifPresent(controller -> {
            SessionId sessionId = requireSessionId(request);
            controller.setSessionValue(sessionId, LOGGING_LEVEL, setLevelRequest.level());
        });

        return ImmutableMap.of();
    }

    private record ResourceTemplateMatch(ResourceTemplateEntry entry, ResourceTemplateValues values) {}

    private Optional<ResourceTemplateMatch> findResourceTemplate(String uri)
    {
        return resourceTemplates.entrySet()
                .stream()
                .flatMap(entry -> {
                    UriTemplate uriTemplate = entry.getKey();
                    Map<String, String> variables = new HashMap<>();
                    if (uriTemplate.match(uri, variables)) {
                        return Stream.of(new ResourceTemplateMatch(entry.getValue(), new ResourceTemplateValues(variables)));
                    }
                    return Stream.of();
                })
                .findFirst();
    }
}
