package org.example.smashhub.chat.ai.service;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.catalog.entity.Product;
import org.example.smashhub.catalog.repository.ProductRepository;
import org.example.smashhub.chat.ai.config.AiProperties;
import org.example.smashhub.common.enums.ProductStatus;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.repository.OrderRepository;
import org.example.smashhub.user.entity.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AiCatalogRagService {

    ProductRepository productRepository;
    OrderRepository orderRepository;
    AiProperties aiProperties;

    /**
     * Dựng system prompt tăng cường kiến thức sản phẩm và đơn hàng (RAG context).
     */
    @Transactional(readOnly = true)
    public String buildSystemPrompt(User customer, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bạn là 'Trợ lý AI SmashHub' - Chuyên viên tư vấn am hiểu dụng cụ cầu lông của sàn TMĐT SmashHub Việt Nam.\n");
        sb.append("Phong cách: Thân thiện, lịch sự, nhiệt tình, tư vấn đúng thông số kỹ thuật (trọng lượng 3U/4U, độ dẻo thân vợt, sức căng cước lbs/kg, lối chơi công thủ/tấn công/điều cầu).\n");
        sb.append("Quy tắc bắt buộc:\n");
        sb.append("- Trả lời súc tích, ngắn gọn, phù hợp hiển thị trong khung chat nhỏ trên điện thoại.\n");
        sb.append("- Giá tiền ghi rõ kèm đơn vị VNĐ.\n");
        sb.append("- Khi khách hỏi về đổi trả, giao hàng hoặc căng cước, áp dụng đúng chính sách của SmashHub.\n");
        sb.append("- Nếu khách hàng yêu cầu gặp nhân viên hoặc có vấn đề khiếu nại phức tạp, hãy trả lời lịch sự và hướng dẫn khách đợi nhân viên hỗ trợ.\n\n");

        // 1. Trích xuất ngữ cảnh đơn hàng nếu khách hỏi về đơn hàng / vận chuyển
        String lower = userMessage.toLowerCase(Locale.ROOT);
        if (customer != null && customer.getId() != null && (lower.contains("đơn") || lower.contains("giao") || lower.contains("hàng") || lower.contains("vận chuyển") || lower.contains("mã đơn"))) {
            int limit = aiProperties.getRag().getMaxContextOrders();
            List<Order> recentOrders = orderRepository.findByUserIdOrderByCreateDateDesc(customer.getId(), PageRequest.of(0, limit)).getContent();
            if (recentOrders != null && !recentOrders.isEmpty()) {
                sb.append("--- THÔNG TIN ĐƠN HÀNG GẦN ĐÂY CỦA KHÁCH ---\n");
                for (int i = 0; i < recentOrders.size(); i++) {
                    Order ord = recentOrders.get(i);
                    sb.append(String.format("- Đơn #%d: Trạng thái [%s], Tổng tiền [%s VNĐ], Người nhận: %s, Ngày tạo: %s\n",
                            ord.getId(),
                            ord.getOrderStatus(),
                            ord.getTotal() != null ? ord.getTotal().toPlainString() : "0",
                            ord.getShippingRecipientName(),
                            ord.getCreateDate() != null ? ord.getCreateDate().toString() : ""));
                }
                sb.append("\n");
            }
        }

        // 2. Trích xuất sản phẩm liên quan từ Catalog
        sb.append("--- SẢN PHẨM NỔI BẬT / LIÊN QUAN TẠI SMASHHUB ---\n");
        int maxProducts = aiProperties.getRag().getMaxContextProducts();
        List<Product> products = productRepository.findAll(PageRequest.of(0, maxProducts)).getContent();
        for (Product p : products) {
            if (p.getProductStatus() == ProductStatus.ACTIVE) {
                sb.append(String.format("- %s (Mã #%d): Giá %s VNĐ | Hãng: %s | Loại: %s\n",
                        p.getName(),
                        p.getId(),
                        p.getPrice() != null ? p.getPrice().toPlainString() : "Liên hệ",
                        p.getBrand() != null ? p.getBrand().getName() : "",
                        p.getType()));
            }
        }

        return sb.toString();
    }
}
