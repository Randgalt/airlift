package io.airlift.mcp.reference;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.airlift.mcp.McpException;
import io.airlift.mcp.McpIdentityMapper;
import io.airlift.mcp.McpMetadata;
import io.airlift.mcp.model.Constants;
import io.airlift.mcp.model.JsonRpcErrorDetail;
import io.airlift.mcp.model.McpIdentity;
import io.airlift.mcp.model.McpIdentity.Authenticated;
import io.airlift.mcp.model.McpIdentity.Error;
import io.airlift.mcp.model.McpIdentity.Unauthenticated;
import io.airlift.mcp.model.McpIdentity.Unauthorized;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import static com.google.common.net.HttpHeaders.WWW_AUTHENTICATE;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static java.util.Objects.requireNonNull;

public class ReferenceFilter
        extends HttpFilter
{
    private static final Set<String> ALLOWED_HTTP_METHODS = ImmutableSet.of("GET", "POST");
    private static final Logger log = Logger.get(ReferenceFilter.class);

    private final ReferenceServerTransport transport;
    private final McpMetadata metadata;
    private final Optional<McpIdentityMapper> identityMapper;

    @Inject
    public ReferenceFilter(ReferenceServerTransport transport, McpMetadata metadata, Optional<McpIdentityMapper> identityMapper)
    {
        this.transport = requireNonNull(transport, "transport is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.identityMapper = requireNonNull(identityMapper, "identityMapper is null");
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException
    {
        if (!isMcpRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        if (identityMapper.isPresent()) {
            McpIdentity identity = identityMapper.get().map(request);
            try {
                switch (identity) {
                    case Authenticated<?> authenticated -> {
                        request.setAttribute(Constants.MCP_IDENTITY_ATTRIBUTE, authenticated.identity());
                        request.setAttribute(Constants.HTTP_RESPONSE_ATTRIBUTE, response);

                        transport.service(request, response);
                    }
                    case Unauthenticated unauthenticated -> {
                        response.setContentType(APPLICATION_JSON);
                        response.sendError(SC_UNAUTHORIZED, unauthenticated.message());
                        unauthenticated.authenticateHeaders()
                                .forEach(header -> response.addHeader(WWW_AUTHENTICATE, header));
                    }
                    case Unauthorized unauthorized -> {
                        response.setContentType(APPLICATION_JSON);
                        response.sendError(SC_FORBIDDEN, unauthorized.message());
                    }
                    case Error error -> {
                        log.error(error.cause(), "An error was thrown during MCP authentication");
                        throw error.cause();
                    }
                }
            }
            catch (McpException mcpException) {
                log.debug(mcpException, "Request: %s", request.getRequestURI());
                JsonRpcErrorDetail errorDetail = mcpException.errorDetail();
                throw new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(errorDetail.code(), errorDetail.message(), errorDetail.data()));
            }
        }
    }

    private boolean isMcpRequest(HttpServletRequest request)
    {
        if (ALLOWED_HTTP_METHODS.contains(request.getMethod())) {
            return metadata.uriPath().equals(request.getRequestURI());
        }
        return false;
    }
}
