package com.ecom.order;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ecom.security.SystemActor;

/**
 * H2/R1: every minute, cancel unpaid orders past their payment deadline and give their stock back.
 * One order per transaction, so the job never holds several orders' product locks at once (no deadlock with
 * checkout), and one bad order can't block the rest: it is logged and retried on the next run.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);
    private static final int MAX_PER_RUN = 1_000;

    private final OrderService orderService;

    public OrderExpiryJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void run() {
        List<UUID> candidates = orderService.findOverdueUnpaid(MAX_PER_RUN);
        int expired = 0;
        for (UUID orderId : candidates) {
            try {
                if (SystemActor.call(() -> orderService.expireIfOverdue(orderId))) { // own transaction each
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire order {} ({}); will retry next run", orderId, e.getClass().getSimpleName());
            }
        }
        if (expired > 0) {
            log.info("Expired {} unpaid orders", expired);
        }
    }
}
