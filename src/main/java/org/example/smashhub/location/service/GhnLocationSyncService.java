package org.example.smashhub.location.service;

public interface GhnLocationSyncService {

    /**
     * Đồng bộ toàn bộ Tỉnh/Thành, Quận/Huyện, Phường/Xã từ GHN Master Data vào DB (upsert).
     *
     * @return Báo cáo kết quả đồng bộ (số lượng province, district, ward đã sync)
     */
    String syncAllLocations();
}

