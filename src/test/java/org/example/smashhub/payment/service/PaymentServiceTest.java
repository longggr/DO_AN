package org.example.smashhub.payment.service;

import org.example.smashhub.common.enums.IpnStatus;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.config.VnpayConfig;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.repository.OrderStatusHistoryRepository;
import org.example.smashhub.order.service.OrderStateMachine;
import org.example.smashhub.payment.dto.VnpayIpnResponse;
import org.example.smashhub.payment.entity.PaymentTransaction;
import org.example.smashhub.payment.repository.PaymentTransactionRepository;
import org.example.smashhub.payment.service.impl.PaymentServiceImpl;
import org.example.smashhub.payment.util.VnpayUtils;
import org.example.smashhub.shipping.service.ShippingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    VnpayConfig vnpayConfig;

    @Mock
    PaymentTransactionRepository transactionRepository;

    @Mock
    OrderRepository orderRepository;

    @Mock
    OrderStateMachine orderStateMachine;

    @Mock
    ShippingService shippingService;

    @Mock
    OrderStatusHistoryRepository statusHistoryRepository;

    @InjectMocks
    PaymentServiceImpl paymentService;

    final String SECRET_KEY = "SECRET_KEY_123456";
    PaymentTransaction sampleTx;
    Order sampleOrder;

    @BeforeEach
    void setUp() {
        lenient().when(vnpayConfig.getHashSecret()).thenReturn(SECRET_KEY);

        sampleOrder = Order.builder()
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .total(new BigDecimal("500000.00"))
                .build();
        sampleOrder.setId(101L);

        sampleTx = PaymentTransaction.builder()
                .order(sampleOrder)
                .txnRef("101_1700000000")
                .amount(new BigDecimal("500000.00"))
                .status("PENDING")
                .ipnProcessed(IpnStatus.PENDING)
                .build();
    }

    private Map<String, String> createSignedParams(String txnRef, String amount, String rspCode, String txStatus) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_Amount", amount); // Số tiền * 100
        params.put("vnp_ResponseCode", rspCode);
        params.put("vnp_TransactionStatus", txStatus);
        params.put("vnp_TransactionNo", "14000123");
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_PayDate", "20261105120000");

        String hashData = VnpayUtils.buildHashData(params);
        String hash = VnpayUtils.hmacSHA512(SECRET_KEY, hashData);
        params.put("vnp_SecureHash", hash);
        return params;
    }

    @Test
    @DisplayName("IPN: Chữ ký không hợp lệ phải trả RspCode 97")
    void testIpnInvalidSignature() {
        Map<String, String> params = createSignedParams("101_1700000000", "50000000", "00", "00");
        params.put("vnp_SecureHash", "INVALID_CHECKSUM");

        VnpayIpnResponse response = paymentService.processIpn(params);

        assertEquals("97", response.getRspCode());
        assertEquals("Invalid Checksum", response.getMessage());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("IPN: Không tìm thấy giao dịch phải trả RspCode 01")
    void testIpnOrderNotFound() {
        Map<String, String> params = createSignedParams("UNKNOWN_REF", "50000000", "00", "00");
        when(transactionRepository.findByTxnRefForUpdate("UNKNOWN_REF")).thenReturn(Optional.empty());

        VnpayIpnResponse response = paymentService.processIpn(params);

        assertEquals("01", response.getRspCode());
        assertEquals("Order not Found", response.getMessage());
    }

    @Test
    @DisplayName("IPN: Sai số tiền thanh toán phải trả RspCode 04")
    void testIpnInvalidAmount() {
        // Gửi 200,000 VND trong khi đơn là 500,000 VND
        Map<String, String> params = createSignedParams("101_1700000000", "20000000", "00", "00");
        when(transactionRepository.findByTxnRefForUpdate("101_1700000000")).thenReturn(Optional.of(sampleTx));

        VnpayIpnResponse response = paymentService.processIpn(params);

        assertEquals("04", response.getRspCode());
        assertEquals("Invalid Amount", response.getMessage());
    }

    @Test
    @DisplayName("IPN: Gọi lặp lại khi đã PROCESSED phải trả RspCode 02 (Idempotency)")
    void testIpnAlreadyConfirmed() {
        sampleTx.setIpnProcessed(IpnStatus.PROCESSED);
        sampleTx.setStatus("SUCCESS");

        Map<String, String> params = createSignedParams("101_1700000000", "50000000", "00", "00");
        when(transactionRepository.findByTxnRefForUpdate("101_1700000000")).thenReturn(Optional.of(sampleTx));

        VnpayIpnResponse response = paymentService.processIpn(params);

        assertEquals("02", response.getRspCode());
        assertEquals("Order already confirmed", response.getMessage());
        verify(orderStateMachine, never()).transition(any(), any(), any(), any());
    }

    @Test
    @DisplayName("IPN: Thanh toán thành công đổi đơn sang PAID và tạo vận đơn GHN, trả RspCode 00")
    void testIpnSuccess() {
        Map<String, String> params = createSignedParams("101_1700000000", "50000000", "00", "00");
        when(transactionRepository.findByTxnRefForUpdate("101_1700000000")).thenReturn(Optional.of(sampleTx));
        when(transactionRepository.findByOrderIdAndStatus(101L, "SUCCESS")).thenReturn(List.of());
        when(orderStateMachine.transition(any(), any(), any(), any())).thenReturn(sampleOrder);

        VnpayIpnResponse response = paymentService.processIpn(params);

        assertEquals("00", response.getRspCode());
        assertEquals("Confirm Success", response.getMessage());

        assertEquals("SUCCESS", sampleTx.getStatus());
        assertEquals(IpnStatus.PROCESSED, sampleTx.getIpnProcessed());
        verify(orderStateMachine, times(1)).transition(eq(sampleOrder), eq(OrderStatus.PAID), eq("VNPAY_IPN"), any());
        verify(shippingService, times(1)).createShipmentForOrder(sampleOrder);
    }
}

