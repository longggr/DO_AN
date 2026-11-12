package org.example.smashhub.shipping.service;

import org.example.smashhub.order.entity.Order;
import org.example.smashhub.shipping.dto.GhnWebhookPayload;
import org.example.smashhub.shipping.dto.ShipmentTrackingResponse;
import org.example.smashhub.shipping.dto.ShippingFeeRequest;
import org.example.smashhub.shipping.dto.ShippingFeeResponse;
import org.example.smashhub.shipping.entity.Shipment;

public interface ShippingService {

    ShippingFeeResponse calculateFee(String username, ShippingFeeRequest request);

    Shipment createShipmentForOrder(Order order);

    void cancelShipment(Order order, String reason);

    void handleWebhook(String tokenHeader, GhnWebhookPayload payload);

    ShipmentTrackingResponse getTrackingInfo(Long orderId, String username);

    Shipment retryShipment(Long orderId);
}

