package com.ecom.finance;

import java.util.List;
import java.util.Objects;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ecom.common.error.ApiException;
import com.ecom.finance.LedgerService.Posting;
import com.ecom.inventory.StockValuationEvent;
import com.ecom.order.CustomerOrder;
import com.ecom.order.OrderEvents;
import com.ecom.order.OrderRepository;
import com.ecom.purchasing.GoodsReceivedEvent;

/**
 * M12: other modules announce business facts; finance turns them into journal entries. Listeners are
 * synchronous and run inside the publisher's transaction, so accounting can never drift from operations.
 */
@Component
public class AccountingListener {

    private final LedgerService ledger;
    private final FinanceService finance;
    private final OrderRepository orders;

    public AccountingListener(LedgerService ledger, FinanceService finance, OrderRepository orders) {
        this.ledger = ledger;
        this.finance = finance;
        this.orders = orders;
    }

    /**
     * Shipment: recognise revenue (deposit -> revenue) and cost of goods sold. R4: COGS is the exact value
     * that left inventory at shipment (recorded on each line), so GL 1200 moves by the same amount as stock.
     */
    @EventListener
    @Transactional
    public void onOrderShipped(OrderEvents.OrderShipped event) {
        CustomerOrder order = order(event.orderId());
        long revenue = order.getSubtotalMinor();
        long cogs = order.getItems().stream()
                .mapToLong(i -> Objects.requireNonNull(i.getShippedCostMinor(), "shipment cost not recorded"))
                .reduce(0L, Math::addExact);
        ledger.post("Revenue and cost of goods for shipped order " + order.getOrderNumber(), "order", order.getId(),
                List.of(Posting.debit(LedgerAccounts.CUSTOMER_DEPOSITS, revenue),
                        Posting.credit(LedgerAccounts.SALES_REVENUE, revenue),
                        Posting.debit(LedgerAccounts.COST_OF_GOODS_SOLD, cogs),
                        Posting.credit(LedgerAccounts.INVENTORY, cogs)));
    }

    @EventListener
    @Transactional
    public void onPaidOrderCancelled(OrderEvents.PaidOrderCancelled event) {
        finance.refundForCancellation(order(event.orderId()));
    }

    @EventListener
    @Transactional
    public void onZeroTotalOrderSettled(OrderEvents.ZeroTotalOrderSettled event) {
        finance.invoiceZeroTotalOrder(order(event.orderId()));
    }

    @EventListener
    @Transactional
    public void onGoodsReceived(GoodsReceivedEvent event) {
        ledger.post(event.description(), "goods_receipt", event.receiptId(), List.of(
                Posting.debit(LedgerAccounts.INVENTORY, event.totalCostMinor()),
                Posting.credit(LedgerAccounts.ACCOUNTS_PAYABLE, event.totalCostMinor())));
    }

    @EventListener
    @Transactional
    public void onStockValuation(StockValuationEvent event) {
        long v = event.valueMinor();
        List<Posting> postings = switch (event.kind()) {
            case OPENING -> List.of(Posting.debit(LedgerAccounts.INVENTORY, v),
                    Posting.credit(LedgerAccounts.OPENING_EQUITY, v));
            case ADJUSTMENT_GAIN -> List.of(Posting.debit(LedgerAccounts.INVENTORY, v),
                    Posting.credit(LedgerAccounts.INVENTORY_ADJUSTMENTS, v));
            case ADJUSTMENT_LOSS -> List.of(Posting.debit(LedgerAccounts.INVENTORY_ADJUSTMENTS, v),
                    Posting.credit(LedgerAccounts.INVENTORY, v));
        };
        ledger.post(event.description(), "stock_movement", event.movementId(), postings);
    }

    private CustomerOrder order(java.util.UUID id) {
        // Same transaction and persistence context as the publisher, so this is the publisher's managed instance.
        return orders.findById(id).orElseThrow(() -> ApiException.notFound("Order"));
    }
}
