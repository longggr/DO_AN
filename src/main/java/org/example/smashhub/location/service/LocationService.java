package org.example.smashhub.location.service;

import org.example.smashhub.location.dto.response.DistrictResponse;
import org.example.smashhub.location.dto.response.ProvinceResponse;
import org.example.smashhub.location.dto.response.WardResponse;

import java.util.List;

public interface LocationService {

    List<ProvinceResponse> getProvinces();

    List<DistrictResponse> getDistrictsByProvince(Integer provinceId);

    List<WardResponse> getWardsByDistrict(Integer districtId);
}

