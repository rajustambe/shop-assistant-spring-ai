package com.raju.shop;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;

public class ToolDiscoveryTest {

    @Test
    void listTools() {
        for (ToolCallback c : ToolCallbacks.from(new DeliveryTools())) {
            System.out.println("DELIVERY name=" + c.getToolDefinition().name());
            System.out.println("DELIVERY desc=" + c.getToolDefinition().description());
            System.out.println("DELIVERY schema=" + c.getToolDefinition().inputSchema());
        }
        for (ToolCallback c : ToolCallbacks.from(new OrderTools(null))) {
            System.out.println("ORDER name=" + c.getToolDefinition().name());
            System.out.println("ORDER desc=" + c.getToolDefinition().description());
            System.out.println("ORDER schema=" + c.getToolDefinition().inputSchema());
        }
    }
}
