package org.example.smashhub.order.mapper;

import org.example.smashhub.order.dto.response.OrderItemResponse;
import org.example.smashhub.order.dto.response.OrderResponse;
import org.example.smashhub.order.entity.Order;
import org.example.smashhub.order.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface OrderMapper {

    @Mapping(source = "shippingProvince.id", target = "shippingProvinceId")
    @Mapping(source = "shippingProvince.name", target = "shippingProvinceName")
    @Mapping(source = "shippingDistrict.id", target = "shippingDistrictId")
    @Mapping(source = "shippingDistrict.name", target = "shippingDistrictName")
    @Mapping(source = "shippingWard.code", target = "shippingWardCode")
    @Mapping(source = "shippingWard.name", target = "shippingWardName")
    @Mapping(target = "paymentUrl", ignore = true)
    OrderResponse toOrderResponse(Order order);

    @Mapping(source = "variant.id", target = "variantId")
    OrderItemResponse toOrderItemResponse(OrderItem orderItem);

    List<OrderItemResponse> toOrderItemResponseList(List<OrderItem> items);
}

