package com.raju.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

// The TOOLS layer. Each @Tool method is something the LLM can decide to call.
// The `description` is critical: the model reads it to decide WHEN to use the tool.
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private final OrderService orderService;

    public OrderTools(OrderService orderService) {
        this.orderService = orderService;
    }

    @Tool(description = "Look up the status and details of a shop order by its numeric order id")
    public String getOrderStatus(long orderId) {
        log.info("    [TOOL] getOrderStatus(orderId={}) called", orderId);
        String result = orderService.describeOrder(orderId);   // thin adapter → real logic lives in the service
        log.info("    [TOOL] getOrderStatus -> {}", result);
        return result;
    }
}
