package com.ticketflow.booking.controller;

import com.ticketflow.auth.model.User;
import com.ticketflow.booking.dto.BookingDto;
import com.ticketflow.booking.service.BookingService;
import com.ticketflow.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Reserve, pay, cancel and list orders")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    @Operation(summary = "Reserve tickets and create a PENDING order with a payment deadline",
            description = "Send an Idempotency-Key header to make retries safe: a repeated key replays the first response.")
    public ResponseEntity<ApiResponse<BookingDto.OrderResponse>> createOrder(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BookingDto.CreateOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Tickets reserved successfully", bookingService.createOrder(user, request)));
    }

    @PostMapping("/pay")
    @Operation(summary = "Pay a PENDING order (mock payment)")
    public ResponseEntity<ApiResponse<BookingDto.OrderResponse>> payOrder(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BookingDto.PayOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Payment confirmed", bookingService.payOrder(user, request)));
    }

    @PostMapping("/{orderNumber}/cancel")
    @Operation(summary = "Cancel a PENDING order and release its tickets")
    public ResponseEntity<ApiResponse<BookingDto.OrderResponse>> cancelOrder(
            @AuthenticationPrincipal User user,
            @PathVariable String orderNumber) {
        return ResponseEntity.ok(ApiResponse.ok("Order cancelled", bookingService.cancelOrder(user, orderNumber)));
    }

    @GetMapping
    @Operation(summary = "Get current user's order history")
    public ResponseEntity<ApiResponse<List<BookingDto.OrderResponse>>> getMyOrders(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getUserOrders(user)));
    }

    @GetMapping("/{orderNumber}")
    @Operation(summary = "Get order details by order number")
    public ResponseEntity<ApiResponse<BookingDto.OrderResponse>> getOrderByNumber(
            @AuthenticationPrincipal User user,
            @PathVariable String orderNumber) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getOrderByNumber(user, orderNumber)));
    }
}

