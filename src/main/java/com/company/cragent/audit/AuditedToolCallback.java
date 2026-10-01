package com.company.cragent.audit;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Wraps a tool so every call lands in the audit log, including failures. */
public class AuditedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AuditLog audit;

    public AuditedToolCallback(ToolCallback delegate, AuditLog audit) {
        this.delegate = delegate;
        this.audit = audit;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }

    @Override
    public String call(String toolInput) { return call(toolInput, null); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        long t0 = System.currentTimeMillis();
        try {
            String out = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            audit.record("copilot", name, toolInput, true, out, System.currentTimeMillis() - t0);
            return out;
        } catch (RuntimeException e) {
            audit.record("copilot", name, toolInput, false, e.getMessage(), System.currentTimeMillis() - t0);
            throw e;
        }
    }
}
