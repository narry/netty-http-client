/*
 * The MIT License
 *
 * Copyright 2015 Tim Boudreau.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.netty.pool.hacks;

import com.mastfrog.url.HostAndPort;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.OneTimeTask;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Copy of FixedChannelPool since that class in Netty is final.
 */
public final class HackFixedChannelPool extends HackSimpleChannelPool {
    private static final IllegalStateException FULL_EXCEPTION = new IllegalStateException("Too many outstanding acquire operations");
    private static final TimeoutException TIMEOUT_EXCEPTION = new TimeoutException("Acquire operation took longer then configured maximum time");
    static {
        FULL_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
        TIMEOUT_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    public enum AcquireTimeoutAction {
        /**
         * Create a new connection when the timeout is detected.
         */
        NEW, /**
         * Fail the {@link Future} of the acquire call with a
         * {@link TimeoutException}.
         */ FAIL
    }
    private final EventExecutor executor;
    private final long acquireTimeoutNanos;
    private final Runnable timeoutTask;
    // There is no need to worry about synchronization as everything that modified the queue or counts is done
    // by the above EventExecutor.
    private final Queue<AcquireTask> pendingAcquireQueue = new ArrayDeque<AcquireTask>();
    private final int maxConnections;
    private final int maxPendingAcquires;
    private int acquiredChannelCount;
    private int pendingAcquireCount;

    /**
     * Creates a new instance using the {@link ChannelHealthChecker#ACTIVE}.
     *
     * @param bootstrap the {@link Bootstrap} that is used for connections
     * @param handler the {@link ChannelPoolHandler} that will be notified
     * for the different pool actions
     * @param maxConnections the numnber of maximal active connections, once
     * this is reached new tries to acquire a {@link Channel} will be
     * delayed until a connection is returned to the pool again.
     */
    public HackFixedChannelPool(Bootstrap bootstrap, ChannelPoolHandler handler, int maxConnections, ChannelPoolChannelInitializer initializer) {
        this(bootstrap, handler, maxConnections, Integer.MAX_VALUE, initializer);
    }

    /**
     * Creates a new instance using the {@link ChannelHealthChecker#ACTIVE}.
     *
     * @param bootstrap the {@link Bootstrap} that is used for connections
     * @param handler the {@link ChannelPoolHandler} that will be notified
     * for the different pool actions
     * @param maxConnections the numnber of maximal active connections, once
     * this is reached new tries to acquire a {@link Channel} will be
     * delayed until a connection is returned to the pool again.
     * @param maxPendingAcquires the maximum number of pending acquires.
     * Once this is exceed acquire tries will be failed.
     */
    public HackFixedChannelPool(Bootstrap bootstrap, ChannelPoolHandler handler, int maxConnections, int maxPendingAcquires, ChannelPoolChannelInitializer initializer) {
        super(bootstrap, handler, initializer);
        executor = bootstrap.group().next();
        timeoutTask = null;
        acquireTimeoutNanos = -1;
        this.maxConnections = maxConnections;
        this.maxPendingAcquires = maxPendingAcquires;
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap the {@link Bootstrap} that is used for connections
     * @param handler the {@link ChannelPoolHandler} that will be notified
     * for the different pool actions
     * @param healthCheck the {@link ChannelHealthChecker} that will be used
     * to check if a {@link Channel} is still healty when obtain from the
     * {@link ChannelPool}
     * @param action the {@link AcquireTimeoutAction} to use or {@code null}
     * if non should be used. In this case {
     * @param acquireTimeoutMillis} must be {@code -1}.
     * @param acquireTimeoutMillis the time (in milliseconds) after which an
     * pending acquire must complete or the {@link AcquireTimeoutAction}
     * takes place.
     * @param maxConnections the numnber of maximal active connections, once
     * this is reached new tries to acquire a {@link Channel} will be
     * delayed until a connection is returned to the pool again.
     * @param maxPendingAcquires the maximum number of pending acquires.
     * Once this is exceed acquire tries will be failed.
     */
    public HackFixedChannelPool(Bootstrap bootstrap, ChannelPoolHandler handler, ChannelHealthChecker healthCheck, AcquireTimeoutAction action, final long acquireTimeoutMillis, int maxConnections, int maxPendingAcquires, HostAndPort hp, ChannelPoolChannelInitializer initializer) {
        super(bootstrap, handler, healthCheck, initializer);
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections: " + maxConnections + " (expected: >= 1)");
        }
        if (maxPendingAcquires < 1) {
            throw new IllegalArgumentException("maxPendingAcquires: " + maxPendingAcquires + " (expected: >= 1)");
        }
        if (action == null && acquireTimeoutMillis == -1) {
            timeoutTask = null;
            acquireTimeoutNanos = -1;
        } else if (action == null && acquireTimeoutMillis != -1) {
            throw new NullPointerException("action");
        } else if (action != null && acquireTimeoutMillis < 0) {
            throw new IllegalArgumentException("acquireTimeoutMillis: " + acquireTimeoutMillis + " (expected: >= 1)");
        } else {
            acquireTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis);
            switch (action) {
                case FAIL:
                    timeoutTask = new TimeoutTask() {
                        @Override
                        public void onTimeout(AcquireTask task) {
                            // Fail the promise as we timed out.
                            task.promise.setFailure(TIMEOUT_EXCEPTION);
                        }
                    };
                    break;
                case NEW:
                    timeoutTask = new TimeoutTask() {
                        @Override
                        public void onTimeout(AcquireTask task) {
                            // Increment the acquire count and delegate to super to actually acquire a Channel which will
                            // create a new connetion.
                            ++acquiredChannelCount;
                            HackFixedChannelPool.super.acquire(task.promise);
                        }
                    };
                    break;
                default:
                    throw new Error();
            }
        }
        executor = bootstrap.group().next();
        this.maxConnections = maxConnections;
        this.maxPendingAcquires = maxPendingAcquires;
    }

    @Override
    public Future<Channel> acquire(final Promise<Channel> promise) {
        try {
            if (executor.inEventLoop()) {
                acquire0(promise);
            } else {
                executor.execute(new OneTimeTask() {
                    @Override
                    public void run() {
                        acquire0(promise);
                    }
                });
            }
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
        return promise;
    }

    private void acquire0(final Promise<Channel> promise) {
        assert executor.inEventLoop();
        if (acquiredChannelCount < maxConnections) {
            ++acquiredChannelCount;
            assert acquiredChannelCount > 0;
            // We need to create a new promise as we need to ensure the AcquireListener runs in the correct
            // EventLoop
            Promise<Channel> p = executor.newPromise();
            p.addListener(new AcquireListener(promise));
            super.acquire(p);
        } else {
            if (pendingAcquireCount >= maxPendingAcquires) {
                promise.setFailure(FULL_EXCEPTION);
            } else {
                AcquireTask task = new AcquireTask(promise);
                if (pendingAcquireQueue.offer(task)) {
                    ++pendingAcquireCount;
                    if (timeoutTask != null) {
                        task.timeoutFuture = executor.schedule(timeoutTask, acquireTimeoutNanos, TimeUnit.NANOSECONDS);
                    }
                } else {
                    promise.setFailure(FULL_EXCEPTION);
                }
            }
            assert pendingAcquireCount > 0;
        }
    }

    @Override
    public Future<Void> release(final Channel channel, final Promise<Void> promise) {
        final Promise<Void> p = executor.newPromise();
        super.release(channel, p.addListener(new FutureListener<Void>() {
            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                assert executor.inEventLoop();
                if (future.isSuccess()) {
                    decrementAndRunTaskQueue();
                    promise.setSuccess(null);
                } else {
                    Throwable cause = future.cause();
                    // Check if the exception was not because of we passed the Channel to the wrong pool.
                    if (!(cause instanceof IllegalArgumentException)) {
                        decrementAndRunTaskQueue();
                    }
                    promise.setFailure(future.cause());
                }
            }
        }));
        return p;
    }

    private void decrementAndRunTaskQueue() {
        --acquiredChannelCount;
        // We should never have a negative value.
        assert acquiredChannelCount >= 0;
        // Run the pending acquire tasks before notify the original promise so if the user would
        // try to acquire again from the ChannelFutureListener and the pendingAcquireCount is >=
        // maxPendingAcquires we may be able to run some pending tasks first and so allow to add
        // more.
        runTaskQueue();
    }

    private void runTaskQueue() {
        while (acquiredChannelCount <= maxConnections) {
            AcquireTask task = pendingAcquireQueue.poll();
            if (task == null) {
                break;
            }
            // Cancel the timeout if one was scheduled
            ScheduledFuture<?> timeoutFuture = task.timeoutFuture;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
            --pendingAcquireCount;
            ++acquiredChannelCount;
            super.acquire(task.promise);
        }
        // We should never have a negative value.
        assert pendingAcquireCount >= 0;
        assert acquiredChannelCount >= 0;
    }

    // AcquireTask extends AcquireListener to reduce object creations and so GC pressure
    private final class AcquireTask extends AcquireListener {

        final Promise<Channel> promise;
        final long expireNanoTime = System.nanoTime() + acquireTimeoutNanos;
        ScheduledFuture<?> timeoutFuture;

        public AcquireTask(Promise<Channel> promise) {
            super(promise);
            // We need to create a new promise as we need to ensure the AcquireListener runs in the correct
            // EventLoop.
            this.promise = executor.<Channel>newPromise().addListener(this);
        }
    }

    private abstract class TimeoutTask implements Runnable {

        @Override
        public final void run() {
            assert executor.inEventLoop();
            long nanoTime = System.nanoTime();
            for (;;) {
                AcquireTask task = pendingAcquireQueue.peek();
                // Compare nanoTime as descripted in the javadocs of System.nanoTime()
                //
                // See https://docs.oracle.com/javase/7/docs/api/java/lang/System.html#nanoTime()
                // See https://github.com/netty/netty/issues/3705
                if (task == null || nanoTime - task.expireNanoTime < 0) {
                    break;
                }
                pendingAcquireQueue.remove();
                --pendingAcquireCount;
                onTimeout(task);
            }
        }

        public abstract void onTimeout(AcquireTask task);
    }

    private class AcquireListener implements FutureListener<Channel> {

        private final Promise<Channel> originalPromise;

        AcquireListener(Promise<Channel> originalPromise) {
            this.originalPromise = originalPromise;
        }

        @Override
        public void operationComplete(Future<Channel> future) throws Exception {
            assert executor.inEventLoop();
            if (future.isSuccess()) {
                originalPromise.setSuccess(future.getNow());
            } else {
                // Something went wrong try to run pending acquire tasks.
                decrementAndRunTaskQueue();
                originalPromise.setFailure(future.cause());
            }
        }
    }

    @Override
    public void close() {
        executor.execute(new OneTimeTask() {
            @Override
            public void run() {
                for (;;) {
                    AcquireTask task = pendingAcquireQueue.poll();
                    if (task == null) {
                        break;
                    }
                    ScheduledFuture<?> f = task.timeoutFuture;
                    if (f != null) {
                        f.cancel(false);
                    }
                    task.promise.setFailure(new ClosedChannelException());
                }
                acquiredChannelCount = 0;
                pendingAcquireCount = 0;
                HackFixedChannelPool.super.close();
            }
        });
    }
    
}
