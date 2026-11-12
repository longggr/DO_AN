package org.example.smashhub.location.scheduler;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.location.service.GhnLocationSyncService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * [MỚI] Scheduler đồng bộ định kỳ Master Data địa giới hành chính từ GHN vào DB.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LocationSyncScheduler {

    private static final String LOCK_KEY = "lock:location:sync";

    GhnLocationSyncService syncService;
    StringRedisTemplate redisTemplate;

    @Scheduled(cron = "${ghn.sync-cron:0 0 3 * * ?}")
    public void runLocationSync() {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "LOCKED", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("Instance khác đang thực hiện LocationSyncScheduler, bỏ qua.");
            return;
        }

        try {
            log.info("Bắt đầu chạy scheduled đồng bộ địa giới hành chính từ GHN...");
            String result = syncService.syncAllLocations();
            log.info("Scheduled đồng bộ GHN kết thúc: {}", result);
        } catch (Exception e) {
            log.error("Lỗi trong quá trình chạy LocationSyncScheduler: ", e);
        } finally {
            redisTemplate.delete(LOCK_KEY);
        }
    }
}

