package org.example.smashhub.catalog.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductChangedEvent {

    public enum ChangeType {
        CREATED,
        UPDATED,
        DELETED,
        STATUS_CHANGED
    }

    private Long productId;
    private ChangeType changeType;

    public static ProductChangedEvent created(Long productId) {
        return new ProductChangedEvent(productId, ChangeType.CREATED);
    }

    public static ProductChangedEvent updated(Long productId) {
        return new ProductChangedEvent(productId, ChangeType.UPDATED);
    }

    public static ProductChangedEvent deleted(Long productId) {
        return new ProductChangedEvent(productId, ChangeType.DELETED);
    }

    public static ProductChangedEvent statusChanged(Long productId) {
        return new ProductChangedEvent(productId, ChangeType.STATUS_CHANGED);
    }
}

