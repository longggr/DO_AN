package org.example.smashhub.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * [MỚI] Định dạng JSON trả về cho VNPay khi nhận IPN callback.
 * Bắt buộc đúng 2 trường: RspCode và Message.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VnpayIpnResponse {

    private String RspCode;
    private String Message;

    public static VnpayIpnResponse success() {
        return new VnpayIpnResponse("00", "Confirm Success");
    }

    public static VnpayIpnResponse orderNotFound() {
        return new VnpayIpnResponse("01", "Order not Found");
    }

    public static VnpayIpnResponse orderAlreadyConfirmed() {
        return new VnpayIpnResponse("02", "Order already confirmed");
    }

    public static VnpayIpnResponse invalidAmount() {
        return new VnpayIpnResponse("04", "Invalid Amount");
    }

    public static VnpayIpnResponse invalidSignature() {
        return new VnpayIpnResponse("97", "Invalid Checksum");
    }

    public static VnpayIpnResponse systemError() {
        return new VnpayIpnResponse("99", "Unknown error");
    }
}

