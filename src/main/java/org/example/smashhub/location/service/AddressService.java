package org.example.smashhub.location.service;

import org.example.smashhub.location.dto.request.AddressRequest;
import org.example.smashhub.location.dto.response.AddressResponse;

import java.util.List;

public interface AddressService {

    List<AddressResponse> getMyAddresses(String username);

    AddressResponse getMyAddressById(String username, Long id);

    AddressResponse createAddress(String username, AddressRequest request);

    AddressResponse updateAddress(String username, Long id, AddressRequest request);

    void deleteAddress(String username, Long id);

    AddressResponse setPrimaryAddress(String username, Long id);
}

