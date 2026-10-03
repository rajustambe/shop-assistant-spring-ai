package com.raju.shop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Unit test with a MOCKED repository — no real database needed.
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    OrderRepository repo;

    @InjectMocks
    OrderService service;

    @Test
    void describesAnExistingOrder() {
        Order o = mock(Order.class);
        when(o.getId()).thenReturn(1001L);
        when(o.getCustomerName()).thenReturn("Asha");
        when(o.getItem()).thenReturn("Wireless Headphones");
        when(o.getStatus()).thenReturn("SHIPPED");
        when(o.getCity()).thenReturn("Pune");
        when(repo.findById(1001L)).thenReturn(Optional.of(o));

        String result = service.describeOrder(1001L);

        assertTrue(result.contains("SHIPPED"));
        assertTrue(result.contains("Pune"));
        assertTrue(result.contains("Asha"));
    }

    @Test
    void handlesMissingOrder() {
        when(repo.findById(9999L)).thenReturn(Optional.empty());
        assertTrue(service.describeOrder(9999L).contains("No order found"));
    }
}
