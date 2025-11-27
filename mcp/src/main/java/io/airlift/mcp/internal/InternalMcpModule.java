package io.airlift.mcp.internal;

import com.google.inject.Binder;
import com.google.inject.Module;
import io.airlift.mcp.McpServer;
import jakarta.servlet.Filter;

import static com.google.inject.Scopes.SINGLETON;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;

public class InternalMcpModule
        implements Module
{
    @Override
    public void configure(Binder binder)
    {
        binder.bind(InternalMcpServer.class).in(SINGLETON);
        binder.bind(McpServer.class).to(InternalMcpServer.class).in(SINGLETON);

        newSetBinder(binder, Filter.class).addBinding().to(InternalFilter.class).in(SINGLETON);

        configBinder(binder).bindConfig(TaskEmulationConfig.class);
        binder.bind(TaskEmulationDecorator.class).in(SINGLETON);
    }
}
