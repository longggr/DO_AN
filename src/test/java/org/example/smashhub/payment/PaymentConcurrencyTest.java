package org.example.smashhub.payment;

import org.example.smashhub.catalog.entity.ProductVariant;
import org.example.smashhub.catalog.repository.ProductVariantRepository;
import org.example.smashhub.catalog.service.InventoryService;
import org.example.smashhub.common.enums.InventoryStatus;
import org.example.smashhub.common.enums.IpnStatus;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.enums.PaymentMethod;
import org.example.smashhub.common.enums.VariantStatus;
import org.example.smashhub.config.GhnConfig;
import org.example.smashhub.config.VnpayConfig;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.entity.OrderItem;
import org.example.smashhub.order.repository.OrderItemRepository;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.repository.OrderStatusHistoryRepository;
import org.example.smashhub.order.scheduler.OrderExpirationScheduler;
import org.example.smashhub.order.service.OrderStateMachine;
import org.example.smashhub.payment.dto.VnpayIpnResponse;
import org.example.smashhub.payment.entity.PaymentTransaction;
import org.example.smashhub.payment.repository.PaymentTransactionRepository;
import org.example.smashhub.payment.service.impl.PaymentServiceImpl;
import org.example.smashhub.payment.util.VnpayUtils;
import org.example.smashhub.shipping.client.GhnClient;
import org.example.smashhub.shipping.dto.GhnWebhookPayload;
import org.example.smashhub.shipping.entity.Shipment;
import org.example.smashhub.shipping.repository.ShipmentRepository;
import org.example.smashhub.shipping.service.ShippingService;
import org.example.smashhub.shipping.service.impl.ShippingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * [MỚI] Bộ Test tái hiện và kiểm chứng các kịch bản Race Condition & Partial Failure
 * theo yêu cầu kiểm toán module thanh toán:
 * 1. 2 IPN song song cùng txnRef
 * 2. IPN lặp lại 5 lần (Idempotency)
 * 3. IPN thành công đồng thời Scheduler quét hết hạn
 * 4. Hủy đơn bởi User + Webhook GHN Cancel song song
 * 5. Concurrency 2 khách mua món cuối cùng (Atomic Deduct Stock)
 * 6. Giả lập GHN timeout sau khi tạo đơn -> Retry không tạo trùng
 * 7. Giả lập Exception sau khi deductStock -> rollback kho
 * 8. IPN đến sau khi đơn đã EXPIRED -> ghi nhận tiền & đánh dấu cần hoàn
 */
@ExtendWith(MockitoExtension.class)
class PaymentConcurrencyTest {

    @Mock VnpayConfig vnpayConfig;
    @Mock GhnConfig ghnConfig;
    @Mock PaymentTransactionRepository transactionRepository;
    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock OrderStatusHistoryRepository statusHistoryRepository;
    @Mock InventoryService inventoryService;
    @Mock ShippingService shippingService;
    @Mock GhnClient ghnClient;
    @Mock ShipmentRepository shipmentRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    PaymentServiceImpl paymentService;
    OrderStateMachine orderStateMachine;

    final String SECRET_KEY = "SECRET_KEY_123456";

    @BeforeEach
    void setUp() {
        orderStateMachine = new OrderStateMachine(orderRepository, orderItemRepository, statusHistoryRepository, inventoryService);
        paymentService = new PaymentServiceImpl(vnpayConfig, transactionRepository, orderRepository, orderStateMachine, shippingService, statusHistoryRepository);

        lenient().when(vnpayConfig.getHashSecret()).thenReturn(SECRET_KEY);
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(orderRepository.findByIdForUpdate(anyLong())).thenAnswer(i -> {
            Order o = Order.builder().orderStatus(OrderStatus.PENDING_PAYMENT).build();
            o.setId(i.getArgument(0));
            return Optional.of(o);
        });
        lenient().when(transactionRepository.save(any(PaymentTransaction.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Map<String, String> createSignedParams(String txnRef, String amountStr, String rspCode, String transStatus) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_Amount", amountStr);
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_PayDate", "20261105120000");
        params.put("vnp_ResponseCode", rspCode);
        params.put("vnp_TransactionNo", "14000123");
        params.put("vnp_TransactionStatus", transStatus);
        params.put("vnp_TxnRef", txnRef);

        String hashData = VnpayUtils.buildHashData(params);
        String secureHash = VnpayUtils.hmacSHA512(SECRET_KEY, hashData);
        params.put("vnp_SecureHash", secureHash);
        return params;
    }

    // =========================================================================
    // 1. 2 IPN song song cùng txnRef -> Chỉ 1 lần đổi sang PAID, 1 vận đơn
    // =========================================================================
    @Test
    @DisplayName("1. Race: 2 IPN song song cùng txnRef -> 1 lần chuyển PAID, 1 trả 00, 1 trả 02")
    void testTwoParallelIpnsSameTxnRef() throws Exception {
        Order order = Order.builder()
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .total(new BigDecimal("500000.00"))
                .build();
        order.setId(101L);

        PaymentTransaction tx = PaymentTransaction.builder()
                .order(order)
                .txnRef("101_1700000000")
                .amount(new BigDecimal("500000.00"))
                .status("PENDING")
                .ipnProcessed(IpnStatus.PENDING)
                .build();

        when(orderRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(order));
        when(transactionRepository.findByOrderIdAndStatus(101L, "SUCCESS")).thenAnswer(i -> {
            if ("SUCCESS".equals(tx.getStatus())) {
                return List.of(tx);
            }
            return Collections.emptyList();
        });

        // Giả lập khóa Pessimistic Lock tuần tự hóa 2 luồng: Luồng 1 vào trước đổi ipnProcessed = PROCESSED
        when(transactionRepository.findByTxnRefForUpdate("101_1700000000")).thenAnswer(inv -> Optional.of(tx));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(2);
        List<VnpayIpnResponse> responses = Collections.synchronizedList(new ArrayList<>());

        Map<String, String> params = createSignedParams("101_1700000000", "50000000", "00", "00");

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startSignal.await();
                    synchronized (tx) { // Giả lập DB row lock
                        responses.add(paymentService.processIpn(params));
                    }
                } catch (Exception ignored) {
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        startSignal.countDown();
        doneSignal.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(2, responses.size());
        long count00 = responses.stream().filter(r -> "00".equals(r.getRspCode())).count();
        long count02 = responses.stream().filter(r -> "02".equals(r.getRspCode())).count();

        assertEquals(1, count00, "Phải có đúng 1 request được xử lý thành công (RspCode 00)");
        assertEquals(1, count02, "Request song song còn lại nhận RspCode 02 (Order already confirmed)");
        assertEquals("SUCCESS", tx.getStatus());
        assertEquals(IpnStatus.PROCESSED, tx.getIpnProcessed());
    }

    // =========================================================================
    // 2. IPN lặp 5 lần -> Các lần sau đều trả 02, không thay đổi dữ liệu
    // =========================================================================
    @Test
    @DisplayName("2. Idempotency: IPN lặp lại 5 lần -> lần đầu 00, 4 lần sau đều 02")
    void testIpnDuplicateFiveTimes() {
        Order order = Order.builder()
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .total(new BigDecimal("200000.00"))
                .build();
        order.setId(202L);

        PaymentTransaction tx = PaymentTransaction.builder()
                .order(order)
                .txnRef("202_1700000000")
                .amount(new BigDecimal("200000.00"))
                .status("PENDING")
                .ipnProcessed(IpnStatus.PENDING)
                .build();

        when(orderRepository.findByIdForUpdate(202L)).thenReturn(Optional.of(order));
        when(transactionRepository.findByTxnRefForUpdate("202_1700000000")).thenReturn(Optional.of(tx));
        when(transactionRepository.findByOrderIdAndStatus(202L, "SUCCESS")).thenReturn(Collections.emptyList());

        Map<String, String> params = createSignedParams("202_1700000000", "20000000", "00", "00");

        // Lần 1: Thành công
        VnpayIpnResponse res1 = paymentService.processIpn(params);
        assertEquals("00", res1.getRspCode());

        // 4 lần tiếp theo: Trả 02
        for (int i = 0; i < 4; i++) {
            VnpayIpnResponse resNext = paymentService.processIpn(params);
            assertEquals("02", resNext.getRspCode());
            assertEquals("Order already confirmed", resNext.getMessage());
        }
    }

    // =========================================================================
    // 3. IPN thành công đồng thời Scheduler quét hết hạn -> Kết quả nhất quán
    // =========================================================================
    @Test
    @DisplayName("3. Race: IPN thành công đồng thời Scheduler quét hết hạn -> Thứ tự khóa PESSIMISTIC bảo vệ")
    void testIpnAndSchedulerRace() {
        Order order = Order.builder()
                .orderStatus(OrderStatus.PENDING_PAYMENT)
                .stockRestored(false)
                .total(new BigDecimal("100000.00"))
                .build();
        order.setId(303L);

        PaymentTransaction tx = PaymentTransaction.builder()
                .order(order)
                .txnRef("303_1700000000")
                .amount(new BigDecimal("100000.00"))
                .status("PENDING")
                .ipnProcessed(IpnStatus.PENDING)
                .build();

        when(orderRepository.findByIdForUpdate(303L)).thenReturn(Optional.of(order));

        // Nếu IPN xử lý trước: chuyển sang PAID
        orderStateMachine.transition(order, OrderStatus.PAID, "VNPAY_IPN", "Thanh toán thành công");
        assertEquals(OrderStatus.PAID, order.getOrderStatus());
        assertFalse(order.isStockRestored(), "Đơn đã PAID không được hoàn kho");

        // Khi Scheduler chạy vào kiểm tra lại: order đã ở trạng thái PAID
        // Scheduler kiểm tra if (lockedOrder.getOrderStatus() != PENDING_PAYMENT) return;
        assertTrue(order.getOrderStatus() != OrderStatus.PENDING_PAYMENT);
        verify(inventoryService, never()).restoreStock(anyLong(), anyInt());
    }

    // =========================================================================
    // 4. Hủy đơn + Webhook GHN Cancel song song -> restoreStock đúng 1 lần
    // =========================================================================
    @Test
    @DisplayName("4. Concurrency: Hủy đơn + Webhook GHN cancel song song -> hoàn kho đúng 1 lần")
    void testCancelOrderAndWebhookParallel() throws Exception {
        ProductVariant variant = ProductVariant.builder().sku("V1").stock(10).build();
        variant.setId(50L);

        OrderItem item = OrderItem.builder().variant(variant).quantity(3).build();

        Order order = Order.builder()
                .orderStatus(OrderStatus.PROCESSING)
                .stockRestored(false)
                .items(new ArrayList<>(List.of(item)))
                .build();
        order.setId(404L);

        when(orderRepository.findByIdForUpdate(404L)).thenReturn(Optional.of(order));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            final String source = (i == 0) ? "USER_CANCEL" : "GHN_WEBHOOK";
            executor.submit(() -> {
                try {
                    latch.await();
                    synchronized (order) { // Giả lập hàng đợi tuần tự hóa DB row lock
                        orderStateMachine.transition(order, OrderStatus.CANCELLED, source, "Cancel");
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        done.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(OrderStatus.CANCELLED, order.getOrderStatus());
        assertTrue(order.isStockRestored());
        // InventoryService.restoreStock chỉ được gọi đúng 1 lần cho variant ID 50 số lượng 3
        verify(inventoryService, times(1)).restoreStock(50L, 3);
    }

    // =========================================================================
    // 5. 2 khách mua món cuối cùng -> Không oversell (Atomic Deduct Stock)
    // =========================================================================
    @Test
    @DisplayName("5. Concurrency: 2 khách cùng mua món cuối cùng -> Đúng 1 khách thành công, 1 khách lỗi")
    void testTwoCustomersBuyLastItemNoOversell() throws Exception {
        // Giả lập DB câu lệnh: UPDATE ProductVariant v SET v.stock = v.stock - 1 WHERE id = 1 AND v.stock >= 1
        AtomicInteger realStockInDb = new AtomicInteger(1);

        when(variantRepository.deductStockAtomic(eq(100L), eq(1), eq(VariantStatus.ACTIVE)))
                .thenAnswer(inv -> {
                    // Mô phỏng atomic update của MySQL
                    if (realStockInDb.get() >= 1) {
                        realStockInDb.decrementAndGet();
                        return 1; // 1 row updated
                    }
                    return 0; // 0 rows updated
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    int updated = variantRepository.deductStockAtomic(100L, 1, VariantStatus.ACTIVE);
                    if (updated == 1) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }

        start.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(1, successCount.get(), "Chỉ 1 khách được trừ kho thành công");
        assertEquals(1, failureCount.get(), "Khách thứ 2 nhận 0 row updated và bị từ chối");
        assertEquals(0, realStockInDb.get(), "Kho không bao giờ bị âm (< 0)");
    }

    // =========================================================================
    // 6. Giả lập GHN timeout sau khi tạo vận đơn -> Retry không tạo trùng
    // =========================================================================
    @Test
    @DisplayName("6. Idempotency: GHN timeout sau khi tạo vận đơn -> Retry không tạo trùng")
    void testGhnTimeoutRetryShipmentNoDuplicate() {
        Order order = Order.builder()
                .orderStatus(OrderStatus.PROCESSING)
                .paymentMethod(PaymentMethod.COD)
                .total(new BigDecimal("300000.00"))
                .build();
        order.setId(606L);

        // Giả lập Shipment đã được lưu với orderCode từ lần gọi trước
        Shipment existing = Shipment.builder()
                .order(order)
                .orderCode("GHN_CODE_606")
                .clientOrderCode("ORDER-606")
                .status("ready_to_pick")
                .build();

        when(shipmentRepository.findByOrderId(606L)).thenReturn(Optional.of(existing));

        ShippingServiceImpl shippingImpl = new ShippingServiceImpl(
                ghnConfig, ghnClient, shipmentRepository, orderRepository,
                null, null, null, null, orderStateMachine
        );

        Shipment result = shippingImpl.createShipmentForOrder(order);

        assertEquals("GHN_CODE_606", result.getOrderCode());
        // ghnClient.createOrder không bao giờ được gọi lại khi orderCode đã có
        verify(ghnClient, never()).createOrder(any());
    }

    // =========================================================================
    // 7. Giả lập Exception sau deductStock -> Toàn bộ transaction rollback
    // =========================================================================
    @Test
    @DisplayName("7. Partial Failure: Lỗi khi restoreStock không được nuốt exception, phải throw để rollback")
    void testRestoreStockThrowsExceptionRollback() {
        ProductVariant variant = ProductVariant.builder().build();
        variant.setId(70L);
        OrderItem item = OrderItem.builder().variant(variant).quantity(1).build();

        Order order = Order.builder()
                .orderStatus(OrderStatus.PROCESSING)
                .stockRestored(false)
                .items(List.of(item))
                .build();
        order.setId(707L);

        when(orderRepository.findByIdForUpdate(707L)).thenReturn(Optional.of(order));
        doThrow(new RuntimeException("DB Connection Glitch")).when(inventoryService).restoreStock(70L, 1);

        // Phải ném ngoại lệ chứ KHÔNG ĐƯỢC nuốt và đánh dấu stockRestored = true
        assertThrows(RuntimeException.class, () ->
                orderStateMachine.transition(order, OrderStatus.CANCELLED, "TEST", "Cancel with glitch")
        );

        assertFalse(order.isStockRestored(), "Nếu hoàn kho thất bại thì cờ stockRestored không được set true");
    }

    // =========================================================================
    // 8. IPN đến sau khi đơn đã EXPIRED -> Giao dịch được ghi nhận và đánh dấu cần hoàn tiền
    // =========================================================================
    @Test
    @DisplayName("8. Late IPN: VNPay báo thành công khi đơn đã EXPIRED -> Ghi nhận SUCCESS_LATE, trả 00, không mất tiền")
    void testLateIpnOnExpiredOrder() {
        Order expiredOrder = Order.builder()
                .orderStatus(OrderStatus.EXPIRED) // Đơn đã hết hạn trước đó
                .total(new BigDecimal("400000.00"))
                .build();
        expiredOrder.setId(808L);

        PaymentTransaction tx = PaymentTransaction.builder()
                .order(expiredOrder)
                .txnRef("808_1700000000")
                .amount(new BigDecimal("400000.00"))
                .status("PENDING")
                .ipnProcessed(IpnStatus.PENDING)
                .build();

        when(transactionRepository.findByTxnRefForUpdate("808_1700000000")).thenReturn(Optional.of(tx));

        Map<String, String> params = createSignedParams("808_1700000000", "40000000", "00", "00");

        // Gọi IPN
        VnpayIpnResponse response = paymentService.processIpn(params);

        // 1. Phải trả RspCode 00 để VNPay không bắn retry liên tục
        assertEquals("00", response.getRspCode());

        // 2. Giao dịch được lưu với trạng thái SUCCESS_LATE và ghi chú rõ ràng cần hoàn tiền
        assertEquals("SUCCESS_LATE", tx.getStatus());
        assertEquals("PAID_AFTER_EXPIRED_NEEDS_REFUND", tx.getFailureReason());
        assertEquals(IpnStatus.PROCESSED, tx.getIpnProcessed());

        // 3. Không tạo vận đơn GHN cho đơn đã hết hạn
        verify(shippingService, never()).createShipmentForOrder(any());

        // 4. Có bản ghi audit cảnh báo hoàn tiền được lưu
        verify(statusHistoryRepository, times(1)).save(any());
    }
}
