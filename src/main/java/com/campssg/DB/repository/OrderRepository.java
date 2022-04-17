package com.campssg.DB.repository;

import com.campssg.DB.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Order findByOrderId(Long orderId);
    List<Order> findByUser_userId(Long userId);
    List<Order> findByMart_martId(Long martId);
}
