package com.raju.shop;

import org.springframework.stereotype.Service;

// Domain logic for orders lives here — reusable by controllers, tools, MCP, tests.
// The @Tool method is just a thin adapter that calls into this.
@Service
public class OrderService {

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    public String describeOrder(long orderId) {
        return orders.findById(orderId)
                .map(o -> "Order " + o.getId() + " for " + o.getCustomerName()
                        + " — item: " + o.getItem()
                        + ", status: " + o.getStatus()
                        + ", ships to: " + o.getCity())
                .orElse("No order found with id " + orderId);
    }
}
