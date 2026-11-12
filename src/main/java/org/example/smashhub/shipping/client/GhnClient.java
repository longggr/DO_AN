package org.example.smashhub.shipping.client;

import org.example.smashhub.shipping.dto.GhnCreateOrderRequest;
import org.example.smashhub.shipping.dto.GhnCreateOrderResponse;
import org.example.smashhub.shipping.dto.GhnFeeCalculationRequest;
import org.example.smashhub.shipping.dto.GhnServiceInfo;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface GhnClient {

    List<GhnServiceInfo> getAvailableServices(int fromDistrict, int toDistrict);

    BigDecimal calculateFee(GhnFeeCalculationRequest request);

    LocalDateTime getLeadtime(int fromDistrict, String fromWard, int toDistrict, String toWard, int serviceId);

    GhnCreateOrderResponse createOrder(GhnCreateOrderRequest request);

    void cancelOrder(String orderCode);

    String getPrintToken(List<String> orderCodes);

    JsonNode getOrderDetail(String orderCode);
}
