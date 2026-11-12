package org.example.smashhub.location.controller;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.response.ApiResponse;
import org.example.smashhub.location.dto.response.DistrictResponse;
import org.example.smashhub.location.dto.response.ProvinceResponse;
import org.example.smashhub.location.dto.response.WardResponse;
import org.example.smashhub.location.service.GhnLocationSyncService;
import org.example.smashhub.location.service.LocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/locations")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LocationController {

    LocationService locationService;
    GhnLocationSyncService ghnLocationSyncService;

    @GetMapping("/provinces")
    public ResponseEntity<ApiResponse<List<ProvinceResponse>>> getProvinces() {
        return ResponseEntity.ok(ApiResponse.<List<ProvinceResponse>>builder()
                .result(locationService.getProvinces())
                .message("Lấy danh sách tỉnh/thành phố thành công")
                .build());
    }

    @GetMapping("/provinces/{provinceId}/districts")
    public ResponseEntity<ApiResponse<List<DistrictResponse>>> getDistricts(
            @PathVariable Integer provinceId) {
        return ResponseEntity.ok(ApiResponse.<List<DistrictResponse>>builder()
                .result(locationService.getDistrictsByProvince(provinceId))
                .message("Lấy danh sách quận/huyện thành công")
                .build());
    }

    @GetMapping("/districts/{districtId}/wards")
    public ResponseEntity<ApiResponse<List<WardResponse>>> getWards(
            @PathVariable Integer districtId) {
        return ResponseEntity.ok(ApiResponse.<List<WardResponse>>builder()
                .result(locationService.getWardsByDistrict(districtId))
                .message("Lấy danh sách phường/xã thành công")
                .build());
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> syncLocations() {
        String result = ghnLocationSyncService.syncAllLocations();
        return ResponseEntity.ok(ApiResponse.<String>builder()
                .result(result)
                .message("Đồng bộ Master Data từ GHN thành công")
                .build());
    }
}

