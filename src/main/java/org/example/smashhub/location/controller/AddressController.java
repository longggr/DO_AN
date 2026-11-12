package org.example.smashhub.location.controller;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.example.smashhub.common.response.ApiResponse;
import org.example.smashhub.location.dto.request.AddressRequest;
import org.example.smashhub.location.dto.response.AddressResponse;
import org.example.smashhub.location.service.AddressService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AddressController {

    AddressService addressService;

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AddressResponse>>> getMyAddresses() {
        return ResponseEntity.ok(ApiResponse.<List<AddressResponse>>builder()
                .result(addressService.getMyAddresses(currentUsername()))
                .message("Lấy danh sách địa chỉ thành công")
                .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AddressResponse>> getAddressById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.<AddressResponse>builder()
                .result(addressService.getMyAddressById(currentUsername(), id))
                .build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AddressResponse>> createAddress(@RequestBody @Valid AddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<AddressResponse>builder()
                .result(addressService.createAddress(currentUsername(), request))
                .message("Thêm địa chỉ mới thành công")
                .build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AddressResponse>> updateAddress(
            @PathVariable Long id,
            @RequestBody @Valid AddressRequest request) {
        return ResponseEntity.ok(ApiResponse.<AddressResponse>builder()
                .result(addressService.updateAddress(currentUsername(), id, request))
                .message("Cập nhật địa chỉ thành công")
                .build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteAddress(@PathVariable Long id) {
        addressService.deleteAddress(currentUsername(), id);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .message("Xóa địa chỉ thành công")
                .build());
    }

    @PatchMapping("/{id}/primary")
    public ResponseEntity<ApiResponse<AddressResponse>> setPrimaryAddress(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.<AddressResponse>builder()
                .result(addressService.setPrimaryAddress(currentUsername(), id))
                .message("Đặt làm địa chỉ mặc định thành công")
                .build());
    }
}

