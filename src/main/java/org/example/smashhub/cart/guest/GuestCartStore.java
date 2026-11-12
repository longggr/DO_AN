package org.example.smashhub.cart.guest;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.cart.dto.request.AddCartItemRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Giỏ khách trên Redis.
 * <pre>
 * Key:  cart:guest:{token}
 * Type: HASH  field=variantId  value=quantity
 * TTL:  app.cart.guest-ttl-days (mặc định 14 ngày), refresh mỗi lần ghi.
 * </pre>
 */
@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class GuestCartStore {

    private static final String KEY_PREFIX = "cart:guest:";

    StringRedisTemplate redisTemplate;

    @NonFinal
    @Value("${app.cart.guest-ttl-days:14}")
    long ttlDays;

    public String newToken() {
        return UUID.randomUUID().toString();
    }
    
    public boolean exists(String token) {
        if (blank(token)) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(token)));
    }

    public List<GuestLine> get(String token) {
        if (blank(token)) {
            return List.of();
        }
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(token));
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<GuestLine> lines = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            try {
                Long variantId = Long.valueOf(String.valueOf(e.getKey()));
                int qty = Integer.parseInt(String.valueOf(e.getValue()));
                if (qty > 0) {
                    lines.add(new GuestLine(variantId, qty));
                }
            } catch (NumberFormatException ex) {
                log.warn("Skip corrupt guest cart entry token={} field={}", token, e.getKey());
            }
        }
        return lines;
    }

    public void put(String token, Long variantId, int quantity) {
        if (blank(token) || variantId == null) {
            return;
        }
        String k = key(token);
        if (quantity <= 0) {
            redisTemplate.opsForHash().delete(k, String.valueOf(variantId));
        } else {
            redisTemplate.opsForHash().put(k, String.valueOf(variantId), String.valueOf(quantity));
        }
        touchTtl(k);
    }

    public void saveAll(String token, List<GuestLine> lines) {
        if (blank(token)) {
            return;
        }
        String k = key(token);
        redisTemplate.delete(k);
        List<GuestLine> normalized = normalize(lines);
        if (normalized.isEmpty()) {
            return;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (GuestLine line : normalized) {
            map.put(String.valueOf(line.variantId()), String.valueOf(line.quantity()));
        }
        redisTemplate.opsForHash().putAll(k, map);
        touchTtl(k);
    }

    public void remove(String token, Long variantId) {
        if (blank(token) || variantId == null) {
            return;
        }
        String k = key(token);
        redisTemplate.opsForHash().delete(k, String.valueOf(variantId));
        touchTtl(k);
    }

    public void clear(String token) {
        if (!blank(token)) {
            redisTemplate.delete(key(token));
        }
    }

    public static List<GuestLine> normalize(List<GuestLine> raw) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        if (raw != null) {
            for (GuestLine line : raw) {
                if (line == null || line.variantId() == null || line.quantity() == null || line.quantity() <= 0) {
                    continue;
                }
                map.merge(line.variantId(), line.quantity(), Integer::sum);
            }
        }
        List<GuestLine> out = new ArrayList<>(map.size());
        map.forEach((id, qty) -> out.add(new GuestLine(id, qty)));
        return out;
    }

    public static List<GuestLine> fromRequests(List<AddCartItemRequest> items) {
        if (items == null) {
            return List.of();
        }
        List<GuestLine> raw = new ArrayList<>(items.size());
        for (AddCartItemRequest item : items) {
            if (item != null) {
                raw.add(new GuestLine(item.getVariantId(), item.getQuantity()));
            }
        }
        return normalize(raw);
    }

    private void touchTtl(String k) {
        redisTemplate.expire(k, Duration.ofDays(Math.max(ttlDays, 1)));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String key(String token) {
        return KEY_PREFIX + token;
    }

    public record GuestLine(Long variantId, Integer quantity) {}
}
