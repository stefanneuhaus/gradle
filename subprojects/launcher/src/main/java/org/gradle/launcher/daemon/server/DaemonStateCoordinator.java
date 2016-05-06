/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.launcher.daemon.server;

import org.gradle.api.logging.Logging;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.initialization.DefaultBuildCancellationToken;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.concurrent.StoppableExecutor;
import org.gradle.launcher.daemon.common.DaemonState;
import org.gradle.launcher.daemon.server.api.DaemonStateControl;
import org.gradle.launcher.daemon.server.api.DaemonStoppedException;
import org.gradle.launcher.daemon.server.api.DaemonUnavailableException;
import org.slf4j.Logger;

import java.util.Date;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.gradle.launcher.daemon.common.DaemonState.*;

/**
 * A tool for synchronising the state amongst different threads.
 *
 * This class has no knowledge of the Daemon's internals and is designed to be used internally by the daemon to coordinate itself and allow worker threads to control the daemon's busy/idle status.
 *
 * This is not exposed to clients of the daemon.
 */
public class DaemonStateCoordinator implements Stoppable, DaemonStateControl {
    private static final Logger LOGGER = Logging.getLogger(DaemonStateCoordinator.class);

    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final long cancelTimeoutMs;

    private DaemonState state = Started;

    private long lastActivityAt = -1;
    private String currentCommandExecution;
    private Object result;
    private volatile DefaultBuildCancellationToken cancellationToken;

    private final StoppableExecutor executor;
    private final Runnable onStartCommand;
    private final Runnable onFinishCommand;

    public DaemonStateCoordinator(ExecutorFactory executorFactory, Runnable onStartCommand, Runnable onFinishCommand) {
        this(executorFactory, onStartCommand, onFinishCommand, 10 * 1000L);
    }

    DaemonStateCoordinator(ExecutorFactory executorFactory, Runnable onStartCommand, Runnable onFinishCommand, long cancelTimeoutMs) {
        executor = executorFactory.create("Daemon worker");
        this.onStartCommand = onStartCommand;
        this.onFinishCommand = onFinishCommand;
        this.cancelTimeoutMs = cancelTimeoutMs;
        updateActivityTimestamp();
        cancellationToken = new DefaultBuildCancellationToken();
    }

    private void setState(DaemonState state) {
        this.state = state;
        condition.signalAll();
    }

    boolean awaitStop() {
        lock.lock();
        try {
            while (true) {
                try {
                    switch (state) {
                        case Started:
                        case Busy:
                        case Idle:
                            LOGGER.debug("daemon is running. Sleeping until state changes.");
                            condition.await();
                            break;
                        case Broken:
                            throw new IllegalStateException("This daemon is in a broken state.");
                        case StopRequested:
                            LOGGER.debug("daemon stop has been requested. Sleeping until state changes.");
                            condition.await();
                            break;
                        case Stopped:
                            LOGGER.debug("daemon has stopped.");
                            return true;
                    }
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    long getIdleMillis(long currentTimeMillis) {
        if (state == Started || state == Idle) {
            return currentTimeMillis - lastActivityAt;
        } else {
            return 0L;
        }
    }

    public void requestStop() {
        lock.lock();
        try {
            LOGGER.debug("Stop as soon as idle requested. The daemon is busy: {}", isBusy());
            if (isBusy()) {
                beginStopping();
            } else {
                stopNow("stop requested and daemon idle");
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forcibly stops the daemon, even if it is busy.
     *
     * If the daemon is busy and the client is waiting for a response, it may receive “null” from the daemon as the connection may be closed by this method before the result is sent back.
     *
     * @see #requestStop()
     */
    public void stop() {
        stopNow("service stop");
    }

    private void stopNow(String reason) {
        lock.lock();
        try {
            switch (state) {
                case Started:
                case Busy:
                case Idle:
                case Broken:
                case StopRequested:
                    LOGGER.debug("Marking daemon stopped due to {}. The daemon is running a build: {}", reason, isBusy());
                    setState(Stopped);
                    break;
                case Stopped:
                    break;
                default:
                    throw new IllegalStateException("Daemon is in unexpected state: " + state);
            }
        } finally {
            lock.unlock();
        }
    }

    private void beginStopping() {
        switch (state) {
            case Started:
            case Busy:
            case Idle:
            case Broken:
                setState(StopRequested);
                break;
            case StopRequested:
            case Stopped:
                break;
            default:
                throw new IllegalStateException("Daemon is in unexpected state: " + state);
        }
    }

    public void requestForcefulStop() {
        LOGGER.debug("Daemon stop requested.");
        stopNow("forceful stop requested");
    }

    public BuildCancellationToken getCancellationToken() {
        return cancellationToken;
    }

    private void updateCancellationToken() {
        cancellationToken = new DefaultBuildCancellationToken();
    }

    public void cancelBuild() {
        long waitUntil = System.currentTimeMillis() + cancelTimeoutMs;
        Date expiry = new Date(waitUntil);
        LOGGER.debug("Cancel requested: will wait for daemon to become idle.");
        try {
            cancellationToken.cancel();
        } catch (Exception ex) {
            LOGGER.error("Cancel processing failed. Will continue.", ex);
        }

        lock.lock();
        try {
            while (System.currentTimeMillis() < waitUntil) {
                try {
                    switch (state) {
                        case Started:
                        case Idle:
                            LOGGER.debug("Cancel: daemon is idle now.");
                            return;
                        case Busy:
                        case StopRequested:
                            LOGGER.debug("Cancel: daemon is busy, sleeping until state changes.");
                            condition.awaitUntil(expiry);
                            break;
                        case Broken:
                            throw new IllegalStateException("This daemon is in a broken state.");
                        case Stopped:
                            LOGGER.debug("Cancel: daemon has stopped.");
                            return;
                    }
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
            LOGGER.debug("Cancel: daemon is still busy after grace period. Will force stop.");
            stopNow("cancel requested");
        } finally {
            lock.unlock();
        }
    }

    public void runCommand(final Runnable command, String commandDisplayName) throws DaemonUnavailableException {
        onStartCommand(commandDisplayName);
        try {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        command.run();
                        onCommandSuccessful();
                    } catch (Throwable t) {
                        onCommandFailed(t);
                    }
                }
            });
            waitForCommandCompletion();
        } finally {
            onFinishCommand();
        }
    }

    private void waitForCommandCompletion() {
        lock.lock();
        try {
            while ((state == Idle || state == Busy || state == StopRequested) && result == null) {
                try {
                    condition.await();
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
            LOGGER.debug("Command execution: finished waiting for {}. Result {} with state {}", currentCommandExecution, result, state);
            if (result instanceof Throwable) {
                throw UncheckedException.throwAsUncheckedException((Throwable) result);
            }
            if (result != null) {
                return;
            }
            switch (state) {
                case Stopped:
                    throw new DaemonStoppedException();
                case Broken:
                    throw new DaemonUnavailableException("This daemon is broken and will stop.");
                default:
                    throw new IllegalStateException("Daemon is in unexpected state: " + state);
            }
        } finally {
            lock.unlock();
        }
    }

    private void onCommandFailed(Throwable failure) {
        lock.lock();
        try {
            result = failure;
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void onCommandSuccessful() {
        lock.lock();
        try {
            result = this;
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void onStartCommand(String commandDisplayName) {
        lock.lock();
        try {
            switch (state) {
                case Busy:
                    throw new DaemonUnavailableException(String.format("This daemon is currently executing: %s", currentCommandExecution));
                case Broken:
                    throw new DaemonUnavailableException("This daemon is in a broken state and will stop.");
                case StopRequested:
                    throw new DaemonUnavailableException("This daemon is currently stopping.");
                case Stopped:
                    throw new DaemonUnavailableException("This daemon has stopped.");
            }

            LOGGER.debug("Command execution: started {} after {} minutes of idle", commandDisplayName, getIdleMinutes());
            try {
                onStartCommand.run();
                currentCommandExecution = commandDisplayName;
                setState(Busy);
                result = null;
                updateActivityTimestamp();
                updateCancellationToken();
                condition.signalAll();
            } catch (Throwable throwable) {
                setState(Broken);
                throw UncheckedException.throwAsUncheckedException(throwable);
            }
        } finally {
            lock.unlock();
        }
    }

    private void onFinishCommand() {
        lock.lock();
        try {
            LOGGER.debug("Command execution: completed {}", currentCommandExecution);
            result = null;
            updateActivityTimestamp();
            currentCommandExecution = null;
            switch (state) {
                case Busy:
                    try {
                        onFinishCommand.run();
                        setState(Idle);
                        condition.signalAll();
                    } catch (Throwable throwable) {
                        setState(Broken);
                        throw UncheckedException.throwAsUncheckedException(throwable);
                    }
                    break;
                case StopRequested:
                    stopNow("command completed and stop requested");
                    break;
                case Stopped:
                    break;
                default:
                    throw new IllegalStateException("Daemon is in unexpected state: " + state);
            }
        } finally {
            lock.unlock();
        }
    }

    private void updateActivityTimestamp() {
        // TODO(ew): Consider using TimeProvider instead
        long now = System.currentTimeMillis();
        LOGGER.debug("updating lastActivityAt to {}", now);
        lastActivityAt = now;
    }

    private double getIdleMinutes() {
        lock.lock();
        try {
            return (System.currentTimeMillis() - lastActivityAt) / 1000 / 60;
        } finally {
            lock.unlock();
        }
    }

    public long getLastActivityAt() {
        return lastActivityAt;
    }

    boolean isStarted() {
        return state == Started;
    }

    boolean isStopped() {
        return state == Stopped;
    }

    boolean isWillRefuseNewCommands() {
        return state != Idle;
    }

    boolean isIdle() {
        return state == Idle;
    }

    boolean isBusy() {
        return state == Busy;
    }
}
