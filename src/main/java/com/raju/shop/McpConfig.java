package com.raju.shop;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Slice 5: publish the SAME @Tool methods over the MCP protocol.
// The MCP server auto-config picks up this ToolCallbackProvider bean and exposes every tool
// to any MCP client (Claude Desktop, Cursor, another agent) over an SSE endpoint.
// No tool logic is duplicated — MCP is just a second doorway to the tools the ChatClient already uses.
@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider mcpToolProvider(OrderTools orderTools,
                                                DeliveryTools deliveryTools,
                                                PolicyTools policyTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(orderTools, deliveryTools, policyTools)
                .build();
    }
}
