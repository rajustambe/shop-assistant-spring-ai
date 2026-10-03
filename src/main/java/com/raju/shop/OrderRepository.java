package com.raju.shop;

import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data generates the implementation at runtime: findById, findAll, save, etc.
public interface OrderRepository extends JpaRepository<Order, Long> {
}
