package com.ecom.apikey;

/**
 * What one API key is allowed to spend (backend/REVIEW.md §5.3). Scopes answer "what may this agent do";
 * these answer "how much". Any field may be null, meaning no limit of that kind.
 *
 * @param maxOrderValueMinor       hard cap: an order above this is refused outright
 * @param maxOrdersPerDay          hard cap: orders placed by this key in the last 24 hours
 * @param requireApprovalAboveMinor soft cap: an order above this is placed, but a human must approve it
 *                                  before it can be paid
 */
public record AgentLimits(Long maxOrderValueMinor, Integer maxOrdersPerDay, Long requireApprovalAboveMinor) {

    public static final AgentLimits NONE = new AgentLimits(null, null, null);

    public boolean exceedsOrderValue(long totalMinor) {
        return maxOrderValueMinor != null && totalMinor > maxOrderValueMinor;
    }

    public boolean needsApproval(long totalMinor) {
        return requireApprovalAboveMinor != null && totalMinor > requireApprovalAboveMinor;
    }

    public boolean exceedsDailyCount(long ordersToday) {
        return maxOrdersPerDay != null && ordersToday >= maxOrdersPerDay;
    }
}
