package com.ecom.finance;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.common.web.Money;
import com.ecom.common.web.PageResponse;
import com.ecom.finance.FinanceDtos.FinanceOrderResponse;
import com.ecom.order.CustomerOrder;
import com.ecom.order.OrderNumbers;
import com.ecom.order.OrderService;
import com.ecom.order.OrderStatus;
import com.ecom.user.UserRepository;
import com.ecom.user.UserSummary;

/**
 * BE-1: the orders finance can record payments against. Read-only and narrow on purpose: finance gets the order
 * number, customer, total and deadline, but not the address or items that orders:admin sees.
 */
@Service
public class FinanceOrderQueries {

    private final OrderService orderService;
    private final UserRepository users;

    public FinanceOrderQueries(OrderService orderService, UserRepository users) {
        this.orderService = orderService;
        this.users = users;
    }

    /**
     * status defaults to PENDING_PAYMENT (the payment queue) unless an order number is given: a number finds that
     * order in any status, e.g. a late payment for an order that already expired.
     */
    @Transactional(readOnly = true)
    public PageResponse<FinanceOrderResponse> list(OrderStatus status, String orderNumber, int page, int size) {
        OrderStatus effective = status == null && OrderNumbers.normalize(orderNumber) == null
                ? OrderStatus.PENDING_PAYMENT : status;
        Page<CustomerOrder> result = orderService.search(effective, orderNumber, page, size);
        List<UUID> customerIds = result.getContent().stream().map(CustomerOrder::getUserId).distinct().toList();
        Map<UUID, UserSummary> customers = customerIds.isEmpty() ? Map.of()
                : users.findSummaries(customerIds).stream().collect(Collectors.toMap(UserSummary::id, Function.identity()));
        return PageResponse.of(result.map(o -> {
            UserSummary customer = customers.get(o.getUserId());
            return new FinanceOrderResponse(o.getId(), o.getOrderNumber(), o.getStatus(), o.getCancelReason(),
                    o.getUserId(), customer == null ? null : customer.email(), customer == null ? null : customer.fullName(),
                    Money.of(o.getTotalMinor(), o.getCurrency()), o.getExpiresAt(), o.getPaidAt(), o.getCreatedAt());
        }));
    }
}
