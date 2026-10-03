package com.raju.shop;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A shop order row. "orders" (not "order") because ORDER is a reserved SQL word.
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private Long id;
    private String customerName;
    private String item;
    private String status;   // PROCESSING, SHIPPED, DELIVERED, CANCELLED
    private String city;

    protected Order() {}     // JPA needs a no-arg constructor

    public Long getId() { return id; }
    public String getCustomerName() { return customerName; }
    public String getItem() { return item; }
    public String getStatus() { return status; }
    public String getCity() { return city; }
}
