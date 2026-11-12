package org.example.smashhub.location.service.impl;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.location.dto.response.DistrictResponse;
import org.example.smashhub.location.dto.response.ProvinceResponse;
import org.example.smashhub.location.dto.response.WardResponse;
import org.example.smashhub.location.repository.DistrictRepository;
import org.example.smashhub.location.repository.ProvinceRepository;
import org.example.smashhub.location.repository.WardRepository;
import org.example.smashhub.location.service.GhnLocationSyncService;
import org.example.smashhub.location.service.LocationService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class LocationServiceImpl implements LocationService {

    ProvinceRepository provinceRepository;
    DistrictRepository districtRepository;
    WardRepository wardRepository;
    GhnLocationSyncService ghnLocationSyncService;

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "locations:provinces", unless = "#result == null || #result.isEmpty()")
    public List<ProvinceResponse> getProvinces() {
        List<ProvinceResponse> provinces = provinceRepository.findAllByOrderByNameAsc().stream()
                .map(p -> ProvinceResponse.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .build())
                .toList();

        if (provinces.isEmpty()) {
            try {
                ghnLocationSyncService.syncAllLocations();
                return provinceRepository.findAllByOrderByNameAsc().stream()
                        .map(p -> ProvinceResponse.builder()
                                .id(p.getId())
                                .name(p.getName())
                                .build())
                        .toList();
            } catch (Exception e) {
                return List.of();
            }
        }

        return provinces;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "locations:districts", key = "#provinceId", unless = "#result == null || #result.isEmpty()")
    public List<DistrictResponse> getDistrictsByProvince(Integer provinceId) {
        return districtRepository.findByProvinceIdOrderByNameAsc(provinceId).stream()
                .map(d -> DistrictResponse.builder()
                        .id(d.getId())
                        .provinceId(d.getProvince().getId())
                        .name(d.getName())
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "locations:wards", key = "#districtId", unless = "#result == null || #result.isEmpty()")
    public List<WardResponse> getWardsByDistrict(Integer districtId) {
        return wardRepository.findByDistrictIdOrderByNameAsc(districtId).stream()
                .map(w -> WardResponse.builder()
                        .code(w.getCode())
                        .districtId(w.getDistrict().getId())
                        .name(w.getName())
                        .build())
                .toList();
    }
}

