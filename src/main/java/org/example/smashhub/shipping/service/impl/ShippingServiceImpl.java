package org.example.smashhub.shipping.service.impl;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.cart.entity.CartItem;
import org.example.smashhub.cart.repository.CartItemRepository;
import org.example.smashhub.catalog.entity.ProductAttribute;
import org.example.smashhub.catalog.repository.ProductAttributeRepository;
import org.example.smashhub.common.enums.OrderStatus;
import org.example.smashhub.common.enums.PaymentMethod;
import org.example.smashhub.common.enums.ShippingMethod;
import org.example.smashhub.config.GhnConfig;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.location.entity.Address;
import org.example.smashhub.location.repository.AddressRepository;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.entity.OrderItem;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.order.service.OrderStateMachine;
import org.example.smashhub.shipping.client.GhnClient;
import org.example.smashhub.shipping.dto.GhnCreateOrderRequest;
import org.example.smashhub.shipping.dto.GhnCreateOrderResponse;
import org.example.smashhub.shipping.dto.GhnFeeCalculationRequest;
import org.example.smashhub.shipping.dto.GhnServiceInfo;
import org.example.smashhub.shipping.dto.GhnWebhookPayload;
import org.example.smashhub.shipping.dto.ShipmentTrackingResponse;
import org.example.smashhub.shipping.dto.ShippingFeeRequest;
import org.example.smashhub.shipping.dto.ShippingFeeResponse;
import org.example.smashhub.shipping.entity.Shipment;
import org.example.smashhub.shipping.repository.ShipmentRepository;
import org.example.smashhub.shipping.service.ShippingService;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ShippingServiceImpl implements ShippingService {

    GhnConfig ghnConfig;
    GhnClient ghnClient;
    ShipmentRepository shipmentRepository;
    OrderRepository orderRepository;
    CartItemRepository cartItemRepository;
    AddressRepository addressRepository;
    ProductAttributeRepository attributeRepository;
    UserRepository userRepository;
    OrderStateMachine orderStateMachine;

    @Override
    @Transactional(readOnly = true)
    public ShippingFeeResponse calculateFee(String username, ShippingFeeRequest request) {
        Integer toDistrictId = request.getToDistrictId();
        String toWardCode = request.getToWardCode();

        if (request.getAddressId() != null) {
            Address address = addressRepository.findById(request.getAddressId())
                    .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));
            toDistrictId = address.getDistrict().getId();
            toWardCode = address.getWard().getCode();
        }

        if (toDistrictId == null || toWardCode == null || toWardCode.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Thiếu thông tin địa chỉ giao hàng (districtId hoặc wardCode)");
        }

        // Tính khối lượng kiện hàng dựa trên các sản phẩm trong giỏ hàng
        int totalWeightGram = 0;
        BigDecimal insuranceValue = BigDecimal.ZERO;

        if (username != null && !username.isBlank()) {
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (userOpt.isPresent()) {
                List<CartItem> cartItems = cartItemRepository.findByUserIdWithDetails(userOpt.get().getId());
                for (CartItem ci : cartItems) {
                    int w = resolveProductWeightGram(ci.getVariant().getColor().getProduct().getId());
                    totalWeightGram += w * ci.getQuantity();
                    insuranceValue = insuranceValue.add(
                            ci.getVariant().getColor().getProduct().getPrice().multiply(BigDecimal.valueOf(ci.getQuantity()))
                    );
                }
            }
        }

        if (totalWeightGram <= 0) {
            totalWeightGram = ghnConfig.getDefaultWeightGram();
        }

        // Lấy danh sách dịch vụ khả dụng từ GHN cho tuyến đường này
        List<GhnServiceInfo> availableServices = ghnClient.getAvailableServices(
                ghnConfig.getFromDistrictId(), toDistrictId);

        if (availableServices.isEmpty()) {
            throw new AppException(ErrorCode.SHIPPING_SERVICE_NOT_AVAILABLE, "GHN không hỗ trợ tuyến giao hàng này");
        }

        // Map STANDARD / EXPRESS sang service_id của GHN
        GhnServiceInfo selectedService = selectGhnService(availableServices, request.getShippingMethod());

        // Tính phí
        GhnFeeCalculationRequest feeReq = GhnFeeCalculationRequest.builder()
                .fromDistrictId(ghnConfig.getFromDistrictId())
                .fromWardCode(ghnConfig.getFromWardCode())
                .toDistrictId(toDistrictId)
                .toWardCode(toWardCode)
                .serviceId(selectedService.getServiceId())
                .serviceTypeId(selectedService.getServiceTypeId())
                .weight(totalWeightGram)
                .length(ghnConfig.getDefaultLengthCm())
                .width(ghnConfig.getDefaultWidthCm())
                .height(ghnConfig.getDefaultHeightCm())
                .insuranceValue(insuranceValue)
                .build();

        BigDecimal fee = ghnClient.calculateFee(feeReq);

        // Lấy thời gian giao dự kiến
        LocalDateTime leadtime = ghnClient.getLeadtime(
                ghnConfig.getFromDistrictId(), ghnConfig.getFromWardCode(),
                toDistrictId, toWardCode, selectedService.getServiceId());

        return ShippingFeeResponse.builder()
                .shippingMethod(request.getShippingMethod())
                .serviceId(selectedService.getServiceId())
                .serviceName(selectedService.getShortName())
                .shippingFee(fee)
                .expectedDeliveryDate(leadtime)
                .build();
    }

    @Override
    @Transactional
    public Shipment createShipmentForOrder(Order order) {
        // Kiểm tra xem đã có vận đơn chưa (idempotent)
        Optional<Shipment> existingOpt = shipmentRepository.findByOrderId(order.getId());
        if (existingOpt.isPresent()) {
            Shipment existing = existingOpt.get();
            if (existing.getOrderCode() != null && !existing.getOrderCode().isBlank()) {
                log.info("Order #{} đã có mã vận đơn GHN {}, bỏ qua tạo mới.", order.getId(), existing.getOrderCode());
                return existing;
            }
        }

        Shipment shipment = existingOpt.orElseGet(() -> Shipment.builder()
                .order(order)
                .clientOrderCode("ORDER-" + order.getId())
                .build());

        try {
            // Lấy dịch vụ GHN phù hợp
            List<GhnServiceInfo> availableServices = ghnClient.getAvailableServices(
                    ghnConfig.getFromDistrictId(), order.getShippingDistrict().getId());
            GhnServiceInfo selectedService = selectGhnService(availableServices, order.getShippingMethod());

            // Tính khối lượng và chuẩn bị danh sách items
            int totalWeight = 0;
            List<GhnCreateOrderRequest.GhnItem> ghnItems = new ArrayList<>();
            if (order.getItems() != null) {
                for (OrderItem oi : order.getItems()) {
                    int w = resolveProductWeightGram(oi.getVariant().getColor().getProduct().getId());
                    totalWeight += w * oi.getQuantity();
                    ghnItems.add(GhnCreateOrderRequest.GhnItem.builder()
                            .name(oi.getProductName())
                            .code(oi.getSku())
                            .quantity(oi.getQuantity())
                            .price(oi.getUnitPrice().intValue())
                            .weight(w)
                            .build());
                }
            }
            if (totalWeight <= 0) {
                totalWeight = ghnConfig.getDefaultWeightGram();
            }

            // Quy tắc COD amount: Đơn COD thu tổng tiền order.total; Đơn VNPAY đã thanh toán thu 0đ
            BigDecimal codAmount = (order.getPaymentMethod() == PaymentMethod.COD)
                    ? order.getTotal()
                    : BigDecimal.ZERO;

            GhnCreateOrderRequest createReq = GhnCreateOrderRequest.builder()
                    .clientOrderCode("ORDER-" + order.getId())
                    .toName(order.getShippingRecipientName())
                    .toPhone(order.getShippingPhone())
                    .toAddress(order.getShippingLine1())
                    .toDistrictId(order.getShippingDistrict().getId())
                    .toWardCode(order.getShippingWard().getCode())
                    .weight(totalWeight)
                    .length(ghnConfig.getDefaultLengthCm())
                    .width(ghnConfig.getDefaultWidthCm())
                    .height(ghnConfig.getDefaultHeightCm())
                    .serviceId(selectedService.getServiceId())
                    .serviceTypeId(selectedService.getServiceTypeId())
                    .codAmount(codAmount)
                    .insuranceValue(order.getSubtotal())
                    .note(order.getNote() != null ? order.getNote() : "SmashHub Order #" + order.getId())
                    .items(ghnItems)
                    .build();

            GhnCreateOrderResponse response = ghnClient.createOrder(createReq);

            shipment.setOrderCode(response.getOrderCode());
            shipment.setServiceId(selectedService.getServiceId());
            shipment.setServiceTypeId(selectedService.getServiceTypeId());
            shipment.setFee(response.getTotalFee());
            shipment.setCodAmount(codAmount);
            shipment.setExpectedDeliveryDate(response.getExpectedDeliveryDate());
            shipment.setStatus("ready_to_pick");
            shipment.setStatusName("Chờ lấy hàng");
            shipment.setRawResponse(response.getRawResponse());
            shipment.setFailureReason(null);

            log.info("Tạo vận đơn GHN thành công cho Order #{}: Mã vận đơn = {}", order.getId(), response.getOrderCode());
            return shipmentRepository.save(shipment);

        } catch (Exception e) {
            log.error("Lỗi khi gọi GHN tạo vận đơn cho Order #{}: {}", order.getId(), e.getMessage());
            // Không rollback Order, ghi nhận lỗi để Admin có thể bấm Retry tạo lại sau
            shipment.setStatus("FAILED");
            shipment.setStatusName("Tạo vận đơn thất bại");
            shipment.setFailureReason(e.getMessage());
            return shipmentRepository.save(shipment);
        }
    }

    @Override
    @Transactional
    public void cancelShipment(Order order, String reason) {
        Optional<Shipment> opt = shipmentRepository.findByOrderId(order.getId());
        if (opt.isPresent()) {
            Shipment shipment = opt.get();
            if (shipment.getOrderCode() != null && !shipment.getOrderCode().isBlank()) {
                try {
                    ghnClient.cancelOrder(shipment.getOrderCode());
                    shipment.setStatus("cancel");
                    shipment.setStatusName("Đã hủy vận đơn");
                    shipmentRepository.save(shipment);
                    log.info("Đã hủy vận đơn GHN {} của Order #{}", shipment.getOrderCode(), order.getId());
                } catch (Exception e) {
                    log.error("Không thể hủy vận đơn GHN {}: {}", shipment.getOrderCode(), e.getMessage());
                }
            }
        }
    }

    @Override
    @Transactional
    public void handleWebhook(String tokenHeader, GhnWebhookPayload payload) {
        log.info("Nhận Webhook từ GHN: OrderCode={}, Status={}", payload.getOrderCode(), payload.getStatus());

        // 1. Xác thực webhook token
        if (ghnConfig.getWebhookToken() != null && !ghnConfig.getWebhookToken().isBlank()) {
            if (tokenHeader == null || !tokenHeader.equals(ghnConfig.getWebhookToken())) {
                log.warn("Webhook GHN bị từ chối: Token không hợp lệ!");
                throw new AppException(ErrorCode.SHIPPING_WEBHOOK_UNAUTHORIZED);
            }
        }

        if (payload.getOrderCode() == null || payload.getOrderCode().isBlank()) {
            log.warn("Webhook GHN thiếu OrderCode, bỏ qua.");
            return;
        }

        // 2. Tìm shipment tương ứng
        Optional<Shipment> shipmentOpt = shipmentRepository.findByOrderCodeWithOrder(payload.getOrderCode());
        if (shipmentOpt.isEmpty()) {
            log.warn("Không tìm thấy Shipment với OrderCode={}", payload.getOrderCode());
            return;
        }

        Shipment shipment = shipmentOpt.get();
        Order order = shipment.getOrder();

        // 3. Cập nhật thông tin shipment
        shipment.setStatus(payload.getStatus());
        if (payload.getStatusName() != null) {
            shipment.setStatusName(payload.getStatusName());
        }
        shipmentRepository.save(shipment);

        // 4. Map trạng thái GHN sang OrderStatus và chuyển trạng thái qua StateMachine
        String ghnStatus = payload.getStatus() != null ? payload.getStatus().toLowerCase() : "";
        OrderStatus targetStatus = null;

        switch (ghnStatus) {
            case "ready_to_pick":
            case "picking":
            case "picked":
                targetStatus = OrderStatus.PROCESSING;
                break;
            case "storing":
            case "transporting":
            case "sorting":
            case "delivering":
                targetStatus = OrderStatus.SHIPPING;
                break;
            case "delivered":
                targetStatus = OrderStatus.DELIVERED;
                break;
            case "returned":
            case "cancel":
                targetStatus = OrderStatus.CANCELLED;
                break;
            default:
                log.info("GHN Status '{}' không đổi trạng thái chính của đơn hàng.", ghnStatus);
                break;
        }

        if (targetStatus != null) {
            // [SỬA] Không lùi trạng thái: nếu đơn đã DELIVERED thì không lùi về SHIPPING hoặc PROCESSING
            if (order.getOrderStatus() == OrderStatus.DELIVERED && targetStatus != OrderStatus.DELIVERED) {
                log.warn("Bỏ qua webhook lùi trạng thái cho Order #{}: Đã DELIVERED, nhận {}", order.getId(), targetStatus);
                return;
            }

            // [SỬA] Nếu đơn VNPay đã thanh toán mà bị GHN hủy hoặc trả hàng, ghi chú rõ ràng để đối soát hoàn tiền
            String note = payload.getDescription() != null ? payload.getDescription() : payload.getStatusName();
            if (targetStatus == OrderStatus.CANCELLED && order.getPaymentMethod() == PaymentMethod.VNPAY) {
                note = "[CẦN HOÀN TIỀN] Đơn VNPay bị GHN hủy/trả hàng. " + (note != null ? note : "");
            }

            orderStateMachine.transition(order, targetStatus, "GHN_WEBHOOK", note);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentTrackingResponse getTrackingInfo(Long orderId, String username) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (username != null && !username.isBlank()) {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
            boolean isOwner = order.getUser() != null && order.getUser().getId().equals(user.getId());
            boolean isAdmin = user.getRole() != null && (
                    "ROLE_ADMIN".equals(user.getRole().getName())
                            || "ADMIN".equals(user.getRole().getName())
                            || "STAFF".equals(user.getRole().getName())
            );
            if (!isOwner && !isAdmin) {
                throw new AppException(ErrorCode.ORDER_ACCESS_DENIED);
            }
        }

        Optional<Shipment> shipmentOpt = shipmentRepository.findByOrderId(orderId);
        if (shipmentOpt.isEmpty()) {
            String status = order.getOrderStatus() != null ? order.getOrderStatus().name() : "PROCESSING";
            String statusName = resolveDefaultStatusName(order.getOrderStatus());

            return ShipmentTrackingResponse.builder()
                    .orderId(order.getId())
                    .orderCode("ORDER-" + order.getId())
                    .clientOrderCode("ORDER-" + order.getId())
                    .status(status)
                    .statusName(statusName)
                    .fee(order.getShippingCost())
                    .codAmount(order.getPaymentMethod() == PaymentMethod.COD ? order.getTotal() : BigDecimal.ZERO)
                    .expectedDeliveryDate(order.getCreateDate() != null ? order.getCreateDate().plusDays(3) : LocalDateTime.now().plusDays(3))
                    .printUrl(null)
                    .build();
        }

        Shipment shipment = shipmentOpt.get();

        String statusName = shipment.getStatusName();
        if ("FAILED".equalsIgnoreCase(shipment.getStatus()) || statusName == null || statusName.isBlank()) {
            statusName = resolveDefaultStatusName(order.getOrderStatus());
        }

        String printUrl = null;
        if (shipment.getOrderCode() != null && !shipment.getOrderCode().isBlank()) {
            String printToken = ghnClient.getPrintToken(List.of(shipment.getOrderCode()));
            if (printToken != null) {
                printUrl = "https://dev-online-gateway.ghn.vn/a5/public-api/printA5?token=" + printToken;
            }
        }

        return ShipmentTrackingResponse.builder()
                .orderId(order.getId())
                .orderCode(shipment.getOrderCode() != null ? shipment.getOrderCode() : shipment.getClientOrderCode())
                .clientOrderCode(shipment.getClientOrderCode())
                .status(shipment.getStatus() != null ? shipment.getStatus() : order.getOrderStatus().name())
                .statusName(statusName)
                .fee(shipment.getFee() != null ? shipment.getFee() : order.getShippingCost())
                .codAmount(shipment.getCodAmount() != null ? shipment.getCodAmount() : (order.getPaymentMethod() == PaymentMethod.COD ? order.getTotal() : BigDecimal.ZERO))
                .expectedDeliveryDate(shipment.getExpectedDeliveryDate() != null ? shipment.getExpectedDeliveryDate() : (order.getCreateDate() != null ? order.getCreateDate().plusDays(3) : LocalDateTime.now().plusDays(3)))
                .printUrl(printUrl)
                .build();
    }

    private String resolveDefaultStatusName(OrderStatus orderStatus) {
        if (orderStatus == null) {
            return "Đang xử lý";
        }
        return switch (orderStatus) {
            case PENDING_PAYMENT -> "Chờ thanh toán";
            case PAID -> "Đã thanh toán - Chờ chuẩn bị hàng";
            case PROCESSING -> "Người bán đang chuẩn bị hàng";
            case SHIPPING -> "Đang vận chuyển";
            case DELIVERED -> "Đã giao hàng thành công";
            case CANCELLED -> "Đơn hàng đã hủy";
            case EXPIRED -> "Đơn hàng đã hết hạn";
            case REFUNDED -> "Đã hoàn tiền";
        };
    }

    @Override
    @Transactional
    public Shipment retryShipment(Long orderId) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        return createShipmentForOrder(order);
    }

    // ---------------------- Helpers ----------------------

    private GhnServiceInfo selectGhnService(List<GhnServiceInfo> availableServices, ShippingMethod method) {
        if (availableServices == null || availableServices.isEmpty()) {
            throw new AppException(ErrorCode.SHIPPING_SERVICE_NOT_AVAILABLE);
        }

        if (method == ShippingMethod.EXPRESS) {
            // Tìm dịch vụ "Nhanh" hoặc service_type_id = 1
            for (GhnServiceInfo s : availableServices) {
                if (s.getServiceTypeId() != null && s.getServiceTypeId() == 1) return s;
                if (s.getShortName() != null && s.getShortName().toLowerCase().contains("nhanh")) return s;
            }
        } else {
            // STANDARD: Tìm dịch vụ "Chuẩn" hoặc service_type_id = 2
            for (GhnServiceInfo s : availableServices) {
                if (s.getServiceTypeId() != null && s.getServiceTypeId() == 2) return s;
                if (s.getShortName() != null && s.getShortName().toLowerCase().contains("chuẩn")) return s;
            }
        }
        // Fallback: chọn dịch vụ đầu tiên trong danh sách khả dụng
        return availableServices.get(0);
    }

    private int resolveProductWeightGram(Long productId) {
        try {
            List<ProductAttribute> attrs = attributeRepository.findByProductId(productId);
            for (ProductAttribute attr : attrs) {
                String name = attr.getAttrName().toLowerCase();
                if (name.contains("weight") || name.contains("trọng lượng")) {
                    String val = attr.getAttrValue().toUpperCase();
                    if (val.contains("3U")) return 87;
                    if (val.contains("4U")) return 83;
                    if (val.contains("5U")) return 78;
                    if (val.contains("2U")) return 92;
                    String numeric = val.replaceAll("[^0-9]", "");
                    if (!numeric.isEmpty()) {
                        int parsed = Integer.parseInt(numeric);
                        if (parsed > 0) return parsed;
                    }
                }
            }
        } catch (Exception ignored) {}
        return ghnConfig.getDefaultWeightGram();
    }
}

