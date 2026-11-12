package org.example.smashhub.location.service.impl;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.config.GhnConfig;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.location.entity.District;
import org.example.smashhub.location.entity.Province;
import org.example.smashhub.location.entity.Ward;
import org.example.smashhub.location.repository.DistrictRepository;
import org.example.smashhub.location.repository.ProvinceRepository;
import org.example.smashhub.location.repository.WardRepository;
import org.example.smashhub.location.service.GhnLocationSyncService;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [MỚI] Đồng bộ dữ liệu địa giới hành chính (Tỉnh, Quận, Phường) từ GHN Master Data API.
 * Thực hiện upsert, không xóa bản ghi cũ để giữ vẹn toàn dữ liệu đơn hàng / địa chỉ.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class GhnLocationSyncServiceImpl implements GhnLocationSyncService {

    GhnConfig ghnConfig;
    ProvinceRepository provinceRepository;
    DistrictRepository districtRepository;
    WardRepository wardRepository;
    CacheManager cacheManager;

    ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public String syncAllLocations() {
        log.info("Bắt đầu đồng bộ Master Data Location từ GHN...");
        RestClient restClient = RestClient.builder()
                .baseUrl(ghnConfig.getBaseUrl())
                .defaultHeader("Token", ghnConfig.getToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        int provinceCount = 0;
        int districtCount = 0;
        int wardCount = 0;

        try {
            // 1. Sync Provinces (GET /master-data/province)
            String provinceJson = restClient.get()
                    .uri("/master-data/province")
                    .retrieve()
                    .body(String.class);

            JsonNode provinceRoot = objectMapper.readTree(provinceJson);
            JsonNode provinceData = provinceRoot.get("data");

            List<Province> provincesToSave = new ArrayList<>();
            if (provinceData != null && provinceData.isArray()) {
                for (JsonNode pNode : provinceData) {
                    int pId = pNode.get("ProvinceID").asInt();
                    String pName = pNode.get("ProvinceName").asText();
                    provincesToSave.add(Province.builder()
                            .id(pId)
                            .name(pName)
                            .build());
                }
            }
            provinceRepository.saveAll(provincesToSave);
            provinceCount = provincesToSave.size();
            log.info("Đã lưu {} tỉnh/thành phố.", provinceCount);

            // 2. Sync Districts & Wards cho từng Province
            for (Province province : provincesToSave) {
                // Rate limit pacing: 50ms giữa các request để tránh HTTP 429
                Thread.sleep(50);

                String districtJson;
                try {
                    districtJson = restClient.post()
                            .uri("/master-data/district")
                            .body(Map.of("province_id", province.getId()))
                            .retrieve()
                            .body(String.class);
                } catch (Exception e) {
                    log.warn("Lỗi khi tải quận/huyện cho tỉnh ID={}: {}", province.getId(), e.getMessage());
                    continue;
                }

                JsonNode districtRoot = objectMapper.readTree(districtJson);
                JsonNode districtData = districtRoot.get("data");

                List<District> districtsToSave = new ArrayList<>();
                if (districtData != null && districtData.isArray()) {
                    for (JsonNode dNode : districtData) {
                        int dId = dNode.get("DistrictID").asInt();
                        String dName = dNode.get("DistrictName").asText();
                        districtsToSave.add(District.builder()
                                .id(dId)
                                .province(province)
                                .name(dName)
                                .build());
                    }
                }
                districtRepository.saveAll(districtsToSave);
                districtCount += districtsToSave.size();

                // 3. Sync Wards cho từng District
                for (District district : districtsToSave) {
                    Thread.sleep(50);

                    String wardJson;
                    try {
                        wardJson = restClient.post()
                                .uri("/master-data/ward")
                                .body(Map.of("district_id", district.getId()))
                                .retrieve()
                                .body(String.class);
                    } catch (Exception e) {
                        log.warn("Lỗi khi tải phường/xã cho huyện ID={}: {}", district.getId(), e.getMessage());
                        continue;
                    }

                    JsonNode wardRoot = objectMapper.readTree(wardJson);
                    JsonNode wardData = wardRoot.get("data");

                    List<Ward> wardsToSave = new ArrayList<>();
                    if (wardData != null && wardData.isArray()) {
                        for (JsonNode wNode : wardData) {
                            String wCode = wNode.get("WardCode").asText();
                            String wName = wNode.get("WardName").asText();
                            wardsToSave.add(Ward.builder()
                                    .code(wCode)
                                    .district(district)
                                    .name(wName)
                                    .build());
                        }
                    }
                    wardRepository.saveAll(wardsToSave);
                    wardCount += wardsToSave.size();
                }
            }

            // Xóa cache redis để client nhận dữ liệu mới nhất
            clearLocationCache();

            String report = String.format("Đồng bộ hoàn tất: %d Tỉnh/Thành, %d Quận/Huyện, %d Phường/Xã",
                    provinceCount, districtCount, wardCount);
            log.info(report);
            return report;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.LOCATION_SYNC_FAILED, "Quá trình đồng bộ bị gián đoạn");
        } catch (Exception e) {
            log.error("Lỗi khi đồng bộ location từ GHN: ", e);
            throw new AppException(ErrorCode.LOCATION_SYNC_FAILED, "Lỗi đồng bộ: " + e.getMessage());
        }
    }

    private void clearLocationCache() {
        if (cacheManager != null) {
            var pCache = cacheManager.getCache("locations:provinces");
            if (pCache != null) pCache.clear();
            var dCache = cacheManager.getCache("locations:districts");
            if (dCache != null) dCache.clear();
            var wCache = cacheManager.getCache("locations:wards");
            if (wCache != null) wCache.clear();
        }
    }
}

