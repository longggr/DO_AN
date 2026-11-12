package org.example.smashhub.payment.service.impl;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.common.enums.IpnStatus;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.config.VnpayConfig;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.entity.OrderStatusHistory;
import org.example.smashhub.order.repository.OrderItemRepository;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.repository.OrderStatusHistoryRepository;
import org.example.smashhub.order.service.OrderStateMachine;
import org.example.smashhub.payment.dto.VnpayIpnResponse;
import org.example.smashhub.payment.dto.VnpayReturnResponse;
import org.example.smashhub.payment.entity.PaymentTransaction;
import org.example.smashhub.payment.repository.PaymentTransactionRepository;
import org.example.smashhub.payment.service.PaymentService;
import org.example.smashhub.payment.util.VnpayUtils;
import org.example.smashhub.shipping.service.ShippingService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PaymentServiceImpl implements PaymentService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final DateTimeFormatter VNP_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    VnpayConfig vnpayConfig;
    PaymentTransactionRepository transactionRepository;
    OrderRepository orderRepository;
    OrderStateMachine orderStateMachine;
    ShippingService shippingService;
    OrderStatusHistoryRepository statusHistoryRepository;

    ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public String createPaymentUrl(Order order, String ipAddress) {
        log.info("Khởi tạo thanh toán VNPay cho Order #{}", order.getId());

        // Tạo mã giao dịch duy nhất cho mỗi lần thanh toán
        String txnRef = order.getId() + "_" + System.currentTimeMillis();

        LocalDateTime now = LocalDateTime.now(VN_ZONE);
        LocalDateTime expireTime = now.plusMinutes(
                vnpayConfig.getExpireMinutes() != null ? vnpayConfig.getExpireMinutes() : 15);

        String vnpCreateDate = now.format(VNP_DATE_FORMATTER);
        String vnpExpireDate = expireTime.format(VNP_DATE_FORMATTER);

        // VNPay yêu cầu số tiền nhân 100, kiểu số nguyên không có phần thập phân
        BigDecimal amountInVnd = order.getTotal().setScale(0, RoundingMode.HALF_UP);
        String vnpAmount = amountInVnd.multiply(BigDecimal.valueOf(100)).toPlainString();

        String clientIp = (ipAddress != null && !ipAddress.isBlank()) ? ipAddress : "127.0.0.1";
        if (clientIp.contains(",")) {
            clientIp = clientIp.split(",")[0].trim();
        }

        String orderInfo = "Thanh toan don hang #" + order.getId();
        orderInfo = VnpayUtils.removeAccents(orderInfo);

        Map<String, String> vnpParams = new LinkedHashMap<>();
        vnpParams.put("vnp_Version", "2.1.0");
        vnpParams.put("vnp_Command", "pay");
        vnpParams.put("vnp_TmnCode", vnpayConfig.getTmnCode());
        vnpParams.put("vnp_Amount", vnpAmount);
        vnpParams.put("vnp_CurrCode", "VND");
        vnpParams.put("vnp_TxnRef", txnRef);
        vnpParams.put("vnp_OrderInfo", orderInfo);
        vnpParams.put("vnp_OrderType", "other");
        vnpParams.put("vnp_Locale", "vn");
        vnpParams.put("vnp_ReturnUrl", vnpayConfig.getReturnUrl());
        vnpParams.put("vnp_IpAddr", clientIp);
        vnpParams.put("vnp_CreateDate", vnpCreateDate);
        vnpParams.put("vnp_ExpireDate", vnpExpireDate);

        // Ký dữ liệu
        String hashData = VnpayUtils.buildHashData(vnpParams);
        String secureHash = VnpayUtils.hmacSHA512(vnpayConfig.getHashSecret(), hashData);

        String paymentUrl = vnpayConfig.getPayUrl() + "?" + hashData + "&vnp_SecureHash=" + secureHash;

        // Lưu bản ghi PaymentTransaction ở trạng thái PENDING
        PaymentTransaction transaction = PaymentTransaction.builder()
                .order(order)
                .provider("VNPAY")
                .txnRef(txnRef)
                .amount(order.getTotal())
                .status("PENDING")
                .ipnProcessed(IpnStatus.PENDING)
                .expireDate(expireTime)
                .ipAddress(clientIp)
                .rawRequestPayload(paymentUrl)
                .build();

        transactionRepository.save(transaction);
        log.info("Tạo payment URL thành công cho Order #{}, txnRef={}", order.getId(), txnRef);

        return paymentUrl;
    }

    @Override
    @Transactional
    public VnpayIpnResponse processIpn(Map<String, String> params) {
        log.info("Nhận callback VNPay IPN: {}", params);

        try {
            // 1. Kiểm tra chữ ký bảo mật (constant-time)
            String secureHash = params.get("vnp_SecureHash");
            String hashData = VnpayUtils.buildHashData(params);

            if (!VnpayUtils.verifySignature(hashData, secureHash, vnpayConfig.getHashSecret())) {
                log.warn("VNPay IPN: Sai chữ ký bảo mật!");
                return VnpayIpnResponse.invalidSignature(); // RspCode 97
            }

            // 2. Tìm bản ghi giao dịch bằng txnRef và khóa dòng (Pessimistic Write Lock)
            String txnRef = params.get("vnp_TxnRef");
            if (txnRef == null || txnRef.isBlank()) {
                return VnpayIpnResponse.orderNotFound(); // RspCode 01
            }

            Optional<PaymentTransaction> txOpt = transactionRepository.findByTxnRefForUpdate(txnRef);
            if (txOpt.isEmpty()) {
                log.warn("VNPay IPN: Không tìm thấy giao dịch với txnRef={}", txnRef);
                return VnpayIpnResponse.orderNotFound(); // RspCode 01
            }

            PaymentTransaction transaction = txOpt.get();
            Order order = transaction.getOrder();

            // 3. Đối chiếu số tiền thanh toán
            String amountStr = params.get("vnp_Amount");
            if (amountStr == null) {
                return VnpayIpnResponse.invalidAmount(); // RspCode 04
            }
            BigDecimal paidAmount = new BigDecimal(amountStr).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (paidAmount.compareTo(transaction.getAmount().setScale(2, RoundingMode.HALF_UP)) != 0) {
                log.warn("VNPay IPN: Sai số tiền! Đã nhận: {}, Kỳ vọng: {}", paidAmount, transaction.getAmount());
                return VnpayIpnResponse.invalidAmount(); // RspCode 04
            }

            // 4. Kiểm tra Idempotency: nếu đã xử lý rồi thì không xử lý lại
            if (transaction.getIpnProcessed() == IpnStatus.PROCESSED) {
                log.info("VNPay IPN: Giao dịch txnRef={} đã được xử lý trước đó (Idempotent).", txnRef);
                return VnpayIpnResponse.orderAlreadyConfirmed(); // RspCode 02
            }

            // 5. Cập nhật kết quả giao dịch
            String responseCode = params.get("vnp_ResponseCode");
            String transactionStatus = params.get("vnp_TransactionStatus");
            String transactionNo = params.get("vnp_TransactionNo");
            String bankCode = params.get("vnp_BankCode");
            String payDateStr = params.get("vnp_PayDate");

            transaction.setResponseCode(responseCode);
            transaction.setTransactionStatus(transactionStatus);
            transaction.setTransactionNo(transactionNo);
            transaction.setBankCode(bankCode);
            try {
                transaction.setRawResponsePayload(objectMapper.writeValueAsString(params));
            } catch (Exception ignored) {}

            if (payDateStr != null && payDateStr.length() == 14) {
                try {
                    transaction.setPayDate(LocalDateTime.parse(payDateStr, VNP_DATE_FORMATTER));
                } catch (Exception ignored) {}
            }

            // Thành công khi ResponseCode = 00 VÀ TransactionStatus = 00
            boolean isSuccess = "00".equals(responseCode) && "00".equals(transactionStatus);

            if (isSuccess) {
                // [SỬA] 1. Kiểm tra kịch bản Late IPN: Khách đã trả tiền nhưng đơn đã EXPIRED hoặc CANCELLED
                // TUYỆT ĐỐI KHÔNG ném lỗi làm rollback giao dịch khiến tiền bị mất dấu.
                // Phải ghi nhận giao dịch thành công và đánh dấu cần đối soát hoàn tiền!
                if (order.getOrderStatus() == OrderStatus.EXPIRED || order.getOrderStatus() == OrderStatus.CANCELLED) {
                    log.warn("CẢNH BÁO: VNPay IPN báo thành công nhưng Order #{} đã ở trạng thái {}! Cần hoàn tiền cho khách.",
                            order.getId(), order.getOrderStatus());
                    transaction.setStatus("SUCCESS_LATE");
                    transaction.setFailureReason("PAID_AFTER_" + order.getOrderStatus() + "_NEEDS_REFUND");
                    transaction.setIpnProcessed(IpnStatus.PROCESSED);
                    transactionRepository.save(transaction);

                    statusHistoryRepository.save(OrderStatusHistory.builder()
                            .order(order)
                            .fromStatus(order.getOrderStatus())
                            .toStatus(order.getOrderStatus())
                            .updatedBy("VNPAY_IPN_LATE")
                            .note(String.format("Khách thanh toán thành công số tiền %s qua VNPay (Mã GD: %s) nhưng đơn đã %s. Cần đối soát hoàn tiền!",
                                    paidAmount, transactionNo, order.getOrderStatus()))
                            .build());

                    return VnpayIpnResponse.success(); // Trả RspCode 00 để VNPay không gọi lại liên tục
                }

                // [SỬA] 2. Kiểm tra Double Payment: Nếu đơn hàng đã có 1 giao dịch khác thành công trước đó
                List<PaymentTransaction> existingSuccess = transactionRepository.findByOrderIdAndStatus(order.getId(), "SUCCESS");
                if (!existingSuccess.isEmpty()) {
                    log.warn("CẢNH BÁO: Đơn hàng #{} đã có giao dịch {} thành công trước đó! Giao dịch txnRef={} là thanh toán trùng, cần hoàn tiền.",
                            order.getId(), existingSuccess.get(0).getTxnRef(), txnRef);
                    transaction.setStatus("DUPLICATE_PAYMENT");
                    transaction.setFailureReason("ORDER_ALREADY_PAID_NEEDS_REFUND");
                    transaction.setIpnProcessed(IpnStatus.PROCESSED);
                    transactionRepository.save(transaction);
                    return VnpayIpnResponse.orderAlreadyConfirmed(); // RspCode 02
                }

                transaction.setStatus("SUCCESS");
                transaction.setIpnProcessed(IpnStatus.PROCESSED);
                transactionRepository.save(transaction);

                // Chuyển trạng thái Order sang PAID
                Order paidOrder = orderStateMachine.transition(order, OrderStatus.PAID, "VNPAY_IPN", "Thanh toán VNPay thành công");

                // [SỬA] 3. Tách gọi API GHN ra ngoài Database Transaction bằng afterCommit
                // Tránh giữ connection DB, tránh lock dòng orders kéo dài và đảm bảo phản hồi IPN tức thì (<50ms)
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                shippingService.createShipmentForOrder(paidOrder);
                            } catch (Exception e) {
                                log.error("Tạo vận đơn GHN sau khi thanh toán VNPay lỗi (Order #{}): {}", paidOrder.getId(), e.getMessage());
                            }
                        }
                    });
                } else {
                    try {
                        shippingService.createShipmentForOrder(paidOrder);
                    } catch (Exception e) {
                        log.error("Tạo vận đơn GHN sau khi thanh toán VNPay lỗi (Order #{}): {}", paidOrder.getId(), e.getMessage());
                    }
                }

                log.info("VNPay IPN thành công cho Order #{}, txnRef={}", order.getId(), txnRef);
            } else {
                transaction.setStatus("FAILED");
                transaction.setFailureReason("VNPay response code: " + responseCode + ", transaction status: " + transactionStatus);
                transaction.setIpnProcessed(IpnStatus.PROCESSED);
                transactionRepository.save(transaction);
                log.warn("VNPay IPN thất bại cho Order #{}, mã lỗi: {}", order.getId(), responseCode);
            }

            return VnpayIpnResponse.success(); // RspCode 00

        } catch (Exception e) {
            log.error("Lỗi ngoại lệ khi xử lý VNPay IPN: ", e);
            return VnpayIpnResponse.systemError(); // RspCode 99
        }
    }

    @Override
    public VnpayReturnResponse processReturn(Map<String, String> params) {
        log.info("Nhận callback VNPay Return URL: {}", params);

        String secureHash = params.get("vnp_SecureHash");
        String hashData = VnpayUtils.buildHashData(params);

        if (!VnpayUtils.verifySignature(hashData, secureHash, vnpayConfig.getHashSecret())) {
            log.warn("VNPay Return: Chữ ký không hợp lệ!");
            return VnpayReturnResponse.builder()
                    .status("FAILED")
                    .message("Chữ ký phản hồi không hợp lệ")
                    .build();
        }

        String txnRef = params.get("vnp_TxnRef");
        String responseCode = params.get("vnp_ResponseCode");
        String amountStr = params.get("vnp_Amount");
        BigDecimal amount = (amountStr != null)
                ? new BigDecimal(amountStr).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Long orderId = null;
        if (txnRef != null && txnRef.contains("_")) {
            try {
                orderId = Long.parseLong(txnRef.split("_")[0]);
            } catch (Exception ignored) {}
        }

        boolean success = "00".equals(responseCode);
        return VnpayReturnResponse.builder()
                .orderId(orderId)
                .txnRef(txnRef)
                .amount(amount)
                .responseCode(responseCode)
                .transactionNo(params.get("vnp_TransactionNo"))
                .status(success ? "SUCCESS" : "FAILED")
                .message(success ? "Giao dịch thanh toán thành công" : "Giao dịch không thành công hoặc đã bị hủy")
                .build();
    }

    @Override
    @Transactional
    public boolean queryTransactionStatus(PaymentTransaction transaction) {
        if (vnpayConfig.getApiUrl() == null || vnpayConfig.getApiUrl().isBlank()) {
            return false;
        }

        try {
            String requestId = UUID.randomUUID().toString();
            String version = "2.1.0";
            String command = "querydr";
            String tmnCode = vnpayConfig.getTmnCode();
            String txnRef = transaction.getTxnRef();
            String orderInfo = "Truy van giao dich " + txnRef;
            String transDate = transaction.getCreateDate().format(VNP_DATE_FORMATTER);
            String createDate = LocalDateTime.now(VN_ZONE).format(VNP_DATE_FORMATTER);
            String ipAddr = "127.0.0.1";

            // Chuỗi dữ liệu ký cho querydr theo tài liệu VNPay:
            // requestId|version|command|tmnCode|txnRef|transDate|createDate|ipAddr|orderInfo
            String rawHash = String.join("|", requestId, version, command, tmnCode, txnRef, transDate, createDate, ipAddr, orderInfo);
            String secureHash = VnpayUtils.hmacSHA512(vnpayConfig.getHashSecret(), rawHash);

            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("vnp_RequestId", requestId);
            reqBody.put("vnp_Version", version);
            reqBody.put("vnp_Command", command);
            reqBody.put("vnp_TmnCode", tmnCode);
            reqBody.put("vnp_TxnRef", txnRef);
            reqBody.put("vnp_OrderInfo", orderInfo);
            reqBody.put("vnp_TransactionDate", transDate);
            reqBody.put("vnp_CreateDate", createDate);
            reqBody.put("vnp_IpAddr", ipAddr);
            reqBody.put("vnp_SecureHash", secureHash);

            RestClient client = RestClient.builder()
                    .baseUrl(vnpayConfig.getApiUrl())
                    .build();

            String response = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(reqBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String responseCode = root.has("vnp_ResponseCode") ? root.get("vnp_ResponseCode").asText() : "";
            String transactionStatus = root.has("vnp_TransactionStatus") ? root.get("vnp_TransactionStatus").asText() : "";

            if ("00".equals(responseCode) && "00".equals(transactionStatus)) {
                log.info("VNPay querydr xác nhận giao dịch {} đã thanh toán thành công!", txnRef);
                Order order = transaction.getOrder();
                if (order.getOrderStatus() == OrderStatus.EXPIRED || order.getOrderStatus() == OrderStatus.CANCELLED) {
                    transaction.setStatus("SUCCESS_LATE");
                    transaction.setFailureReason("PAID_AFTER_" + order.getOrderStatus() + "_NEEDS_REFUND");
                    transaction.setIpnProcessed(IpnStatus.PROCESSED);
                    transactionRepository.save(transaction);
                    return true;
                }

                transaction.setStatus("SUCCESS");
                transaction.setIpnProcessed(IpnStatus.PROCESSED);
                if (root.has("vnp_TransactionNo")) {
                    transaction.setTransactionNo(root.get("vnp_TransactionNo").asText());
                }
                transactionRepository.save(transaction);
                Order paidOrder = orderStateMachine.transition(order, OrderStatus.PAID, "VNPAY_QUERYDR", "Thanh toán thành công qua QueryDR");
                shippingService.createShipmentForOrder(paidOrder);
                return true;
            }
        } catch (Exception e) {
            log.warn("Lỗi khi gọi VNPay querydr cho giao dịch {}: {}", transaction.getTxnRef(), e.getMessage());
        }
        return false;
    }

    @Override
    @Transactional
    public String repay(Long orderId, String username, String ipAddress) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getUsername().equals(username)) {
            throw new AppException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        if (order.getOrderStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new AppException(ErrorCode.ORDER_ALREADY_PAID, "Đơn hàng không ở trạng thái chờ thanh toán");
        }

        return createPaymentUrl(order, ipAddress);
    }
}

