package org.example.smashhub.chat.ai.listener;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.example.smashhub.catalog.event.ProductChangedEvent;
import org.example.smashhub.chat.ai.service.ProductIndexingService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ProductEventListener {

    ProductIndexingService productIndexingService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductChanged(ProductChangedEvent event) {
        log.info("Received ProductChangedEvent: productId={}, type={}", event.getProductId(), event.getChangeType());
        if (event.getChangeType() == ProductChangedEvent.ChangeType.DELETED) {
            productIndexingService.removeProduct(event.getProductId());
        } else {
            productIndexingService.indexProduct(event.getProductId());
        }
    }
}

