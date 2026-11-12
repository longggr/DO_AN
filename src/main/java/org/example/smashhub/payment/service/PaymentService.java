package org.example.smashhub.payment.service;

import org.example.smashhub.order.entity.Order;
import org.example.smashhub.payment.dto.VnpayIpnResponse;
import org.example.smashhub.payment.dto.VnpayReturnResponse;
import org.example.smashhub.payment.entity.PaymentTransaction;

import java.util.Map;

public interface PaymentService {

    String createPaymentUrl(Order order, String ipAddress);

    VnpayIpnResponse processIpn(Map<String, String> params);

    VnpayReturnResponse processReturn(Map<String, String> params);

    boolean queryTransactionStatus(PaymentTransaction transaction);

    String repay(Long orderId, String username, String ipAddress);
}

