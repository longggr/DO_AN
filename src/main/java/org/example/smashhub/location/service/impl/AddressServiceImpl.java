package org.example.smashhub.location.service.impl;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.enums.AddressPriority;
import org.example.smashhub.exception.AppException;
import org.example.smashhub.exception.ErrorCode;
import org.example.smashhub.location.dto.request.AddressRequest;
import org.example.smashhub.location.dto.response.AddressResponse;
import org.example.smashhub.location.entity.Address;
import org.example.smashhub.location.entity.District;
import org.example.smashhub.location.entity.Province;
import org.example.smashhub.location.entity.Ward;
import org.example.smashhub.location.mapper.AddressMapper;
import org.example.smashhub.location.repository.AddressRepository;
import org.example.smashhub.location.repository.DistrictRepository;
import org.example.smashhub.location.repository.ProvinceRepository;
import org.example.smashhub.location.repository.WardRepository;
import org.example.smashhub.location.service.AddressService;
import org.example.smashhub.user.entity.User;
import org.example.smashhub.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AddressServiceImpl implements AddressService {

    AddressRepository addressRepository;
    ProvinceRepository provinceRepository;
    DistrictRepository districtRepository;
    WardRepository wardRepository;
    UserRepository userRepository;
    AddressMapper addressMapper;

    @Override
    @Transactional(readOnly = true)
    public List<AddressResponse> getMyAddresses(String username) {
        User user = requireUser(username);
        return addressMapper.toAddressResponseList(addressRepository.findByUserIdWithDetails(user.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public AddressResponse getMyAddressById(String username, Long id) {
        User user = requireUser(username);
        Address address = addressRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));
        return addressMapper.toAddressResponse(address);
    }

    @Override
    @Transactional
    public AddressResponse createAddress(String username, AddressRequest request) {
        User user = requireUser(username);
        ValidatedLocation location = validateHierarchy(request.getProvinceId(), request.getDistrictId(), request.getWardCode());

        long count = addressRepository.countByUserId(user.getId());
        boolean makePrimary = count == 0 || Boolean.TRUE.equals(request.getPrimary());

        if (makePrimary && count > 0) {
            addressRepository.resetAllPrimaryForUser(user.getId(), AddressPriority.SECONDARY);
        }

        Address address = Address.builder()
                .user(user)
                .recipientName(request.getRecipientName().trim())
                .phone(request.getPhone() != null ? request.getPhone().trim() : user.getPhone())
                .line1(request.getLine1().trim())
                .province(location.province())
                .district(location.district())
                .ward(location.ward())
                .primaryAddress(makePrimary ? AddressPriority.PRIMARY : AddressPriority.SECONDARY)
                .build();

        Address saved = addressRepository.save(address);
        return addressMapper.toAddressResponse(saved);
    }

    @Override
    @Transactional
    public AddressResponse updateAddress(String username, Long id, AddressRequest request) {
        User user = requireUser(username);
        Address address = addressRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));

        ValidatedLocation location = validateHierarchy(request.getProvinceId(), request.getDistrictId(), request.getWardCode());

        if (Boolean.TRUE.equals(request.getPrimary()) && address.getPrimaryAddress() != AddressPriority.PRIMARY) {
            addressRepository.resetAllPrimaryForUser(user.getId(), AddressPriority.SECONDARY);
            address.setPrimaryAddress(AddressPriority.PRIMARY);
        }

        address.setRecipientName(request.getRecipientName().trim());
        address.setPhone(request.getPhone() != null ? request.getPhone().trim() : user.getPhone());
        address.setLine1(request.getLine1().trim());
        address.setProvince(location.province());
        address.setDistrict(location.district());
        address.setWard(location.ward());

        Address saved = addressRepository.save(address);
        return addressMapper.toAddressResponse(saved);
    }

    @Override
    @Transactional
    public void deleteAddress(String username, Long id) {
        User user = requireUser(username);
        Address address = addressRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));

        boolean wasPrimary = address.getPrimaryAddress() == AddressPriority.PRIMARY;
        addressRepository.delete(address);

        // Nếu xóa địa chỉ PRIMARY thì tự động chọn địa chỉ gần nhất còn lại làm PRIMARY
        if (wasPrimary) {
            List<Address> remaining = addressRepository.findByUserIdWithDetails(user.getId());
            if (!remaining.isEmpty()) {
                Address newPrimary = remaining.get(0);
                newPrimary.setPrimaryAddress(AddressPriority.PRIMARY);
                addressRepository.save(newPrimary);
            }
        }
    }

    @Override
    @Transactional
    public AddressResponse setPrimaryAddress(String username, Long id) {
        User user = requireUser(username);
        Address address = addressRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new AppException(ErrorCode.ADDRESS_NOT_FOUND));

        if (address.getPrimaryAddress() != AddressPriority.PRIMARY) {
            addressRepository.resetAllPrimaryForUser(user.getId(), AddressPriority.SECONDARY);
            address.setPrimaryAddress(AddressPriority.PRIMARY);
            address = addressRepository.save(address);
        }
        return addressMapper.toAddressResponse(address);
    }

    // ---------------------- Helpers ----------------------

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
    }

    private record ValidatedLocation(Province province, District district, Ward ward) {}

    private ValidatedLocation validateHierarchy(Integer provinceId, Integer districtId, String wardCode) {
        Province province = provinceRepository.findById(provinceId)
                .orElseThrow(() -> new AppException(ErrorCode.PROVINCE_NOT_FOUND));

        District district = districtRepository.findById(districtId)
                .orElseThrow(() -> new AppException(ErrorCode.DISTRICT_NOT_FOUND));

        Ward ward = wardRepository.findById(wardCode)
                .orElseThrow(() -> new AppException(ErrorCode.WARD_NOT_FOUND));

        // Kiểm tra tính toàn vẹn phân cấp hành chính: district thuộc province, ward thuộc district
        if (!district.getProvince().getId().equals(province.getId())
                || !ward.getDistrict().getId().equals(district.getId())) {
            throw new AppException(ErrorCode.ADDRESS_LOCATION_MISMATCH);
        }

        return new ValidatedLocation(province, district, ward);
    }
}

