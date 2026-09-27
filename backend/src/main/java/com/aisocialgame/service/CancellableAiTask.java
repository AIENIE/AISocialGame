package com.aisocialgame.service;

import io.grpc.Context;
import java.util.concurrent.FutureTask;

/** Cancellation follows the executing RPC context, including a task still waiting in the executor queue. */
public final class CancellableAiTask extends FutureTask<Void> {
    private final Context.CancellableContext context;
    private final AiStreamConcurrencyLimiter.Permit permit;

    public CancellableAiTask(Runnable work, AiStreamConcurrencyLimiter.Permit permit) {
        this(Context.current().withCancellation(), work, permit);
    }

    private CancellableAiTask(Context.CancellableContext context, Runnable work, AiStreamConcurrencyLimiter.Permit permit) {
        super(context.wrap(work), null);
        this.context = context;
        this.permit = permit;
    }

    @Override public boolean cancel(boolean interrupt) {
        context.cancel(new java.util.concurrent.CancellationException("AI response closed"));
        return super.cancel(interrupt);
    }

    @Override protected void done() {
        try { context.close(); } finally { permit.close(); }
    }
}
