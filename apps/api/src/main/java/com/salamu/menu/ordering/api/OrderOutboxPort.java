package com.salamu.menu.ordering.api;

import java.time.Instant;
import java.util.List;

public interface OrderOutboxPort {

    List<ClaimedOutboxEvent> claim(OutboxClaimRequest request);

    OutboxTransitionResult complete(OutboxCompletion completion);

    OutboxTransitionResult retry(OutboxRetry retry);

    OutboxTransitionResult fail(OutboxFailure failure);

    OutboxBacklogSnapshot backlog(Instant now);
}
