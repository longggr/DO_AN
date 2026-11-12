package org.example.smashhub.order.scheduler;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.config.VnpayConfig;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.service.OrderStateMachine;
import org.example.smashhub.payment.entity.PaymentTransaction;
import org.example.smashhub.payment.repository.PaymentTransactionRepository;
import org.example.smashhub.payment.service.PaymentService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [SỬA] Scheduler quét và xử lý các đơn hàng VNPay bị quá hạn thanh toán.
 * - Sử dụng Redis Distributed Lock an toàn với Token UUID + Lua Script release để không giải nhầm lock của instance khác.
 * - Khóa dòng Order bằng PESSIMISTIC_WRITE để chống race condition với IPN đang thanh toán dở.
 * - Gọi VNPay QueryDR kiểm tra đối soát trước khi hủy, tránh hủy nhầm đơn khách đã trả tiền mà IPN bị trễ.
 * - Chuyển sang EXPIRED và hoàn lại tồn kho đúng 1 lần duy nhất (idempotent).
 */
@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class OrderExpirationScheduler {

    private static final String LOCK_KEY = "lock:order:expiration";
    private static final String UNLOCK_LUA_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    OrderRepository orderRepository;
    PaymentTransactionRepository transactionRepository;
    PaymentService paymentService;
    OrderStateMachine orderStateMachine;
    StringRedisTemplate redisTemplate;
    VnpayConfig vnpayConfig;

    @Scheduled(cron = "${app.order.expire-cron:0 * * * * ?}")
    public void scanAndExpireOrders() {
        // [SỬA] Dùng Token ngẫu nhiên cho Redis Lock
        String lockToken = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, lockToken, Duration.ofSeconds(55));
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Instance khác đang chạy OrderExpirationScheduler, bỏ qua lượt quét này.");
            return;
        }

        try {
            int expireMinutes = vnpayConfig.getExpireMinutes() != null ? vnpayConfig.getExpireMinutes() : 15;
            LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(expireMinutes);

            List<Order> expiredCandidates = orderRepository.findByOrderStatusAndCreateDateBefore(
                    OrderStatus.PENDING_PAYMENT, cutoffTime);

            if (expiredCandidates.isEmpty()) {
                return;
            }

            log.info("Tìm thấy {} đơn hàng PENDING_PAYMENT quá hạn cần xử lý đối soát.", expiredCandidates.size());

            for (Order order : expiredCandidates) {
                try {
                    processExpiredOrder(order);
                } catch (Exception e) {
                    log.error("Lỗi khi xử lý hết hạn cho Order #{}: {}", order.getId(), e.getMessage());
                }
            }
        } finally {
            // [SỬA] Giải phóng lock bằng Lua Script chỉ khi đúng Token chủ sở hữu
            try {
                DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_LUA_SCRIPT, Long.class);
                redisTemplate.execute(script, Collections.singletonList(LOCK_KEY), lockToken);
            } catch (Exception e) {
                log.warn("Lỗi khi giải phóng Redis Lock hết hạn đơn hàng: {}", e.getMessage());
            }
        }
    }

    private void processExpiredOrder(Order order) {
        log.info("Đang kiểm tra đối soát trước khi hủy Order #{}", order.getId());

        // [SỬA] Khóa dòng Order và kiểm tra lại trạng thái tươi nhất từ DB
        // Tránh race condition nếu luồng IPN vừa mới chuyển đơn sang PAID ngay trước đó
        Optional<Order> lockedOrderOpt = orderRepository.findByIdForUpdate(order.getId());
        if (lockedOrderOpt.isEmpty()) {
            return;
        }
        Order lockedOrder = lockedOrderOpt.get();
        if (lockedOrder.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            log.info("Order #{} đã đổi trạng thái sang {} (bởi luồng khác), bỏ qua việc hủy đơn.",
                    lockedOrder.getId(), lockedOrder.getOrderStatus());
            return;
        }

        // Lấy giao dịch thanh toán gần nhất
        Optional<PaymentTransaction> txOpt = transactionRepository.findTopByOrderIdOrderByCreateDateDesc(lockedOrder.getId());

        boolean isPaidOnVnpay = false;
        if (txOpt.isPresent()) {
            PaymentTransaction transaction = txOpt.get();
            // Truy vấn lại với VNPay QueryDR
            isPaidOnVnpay = paymentService.queryTransactionStatus(transaction);
        }

        if (isPaidOnVnpay) {
            log.info("Order #{} đã được thanh toán thành công trên VNPay (xác nhận qua QueryDR).", lockedOrder.getId());
            return;
        }

        // Đơn chưa thanh toán và đã quá hạn -> Chuyển sang EXPIRED (tự động hoàn kho 1 lần qua State Machine)
        orderStateMachine.transition(lockedOrder, OrderStatus.EXPIRED, "SCHEDULER",
                "Quá thời gian thanh toán (" + vnpayConfig.getExpireMinutes() + " phút)");

        if (txOpt.isPresent()) {
            PaymentTransaction tx = txOpt.get();
            if ("PENDING".equals(tx.getStatus())) {
                tx.setStatus("EXPIRED");
                transactionRepository.save(tx);
            }
        }

        log.info("Đã đánh dấu hết hạn và hoàn kho cho Order #{}", lockedOrder.getId());
    }
}

