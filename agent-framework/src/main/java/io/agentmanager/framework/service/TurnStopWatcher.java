package io.agentmanager.framework.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.TurnStopSettings;

@Service
public class TurnStopWatcher {

    private static final Logger log = LoggerFactory.getLogger(TurnStopWatcher.class);
    private static final long RECENT_STOP_TTL_MS = 10_000L;
    private static final int MAX_RECENT_STOPS = 4096;

    private final TurnStopStore stopStore;
    private final TurnControlRegistry registry;
    private final TurnStopSettings settings;
    private final ConcurrentHashMap<String, Registration> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentStops = new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Lazy
    private TurnStopNotifier notifier;

    public TurnStopWatcher(TurnStopStore stopStore,
                           TurnControlRegistry registry,
                           TurnStopSettings settings) {
        this.stopStore = stopStore;
        this.registry = registry;
        this.settings = settings;
    }

    private static final class Registration {
        final String sessionId;
        final String replyId;
        final boolean queued;
        final AtomicBoolean deliveredLogged = new AtomicBoolean(false);
        final AtomicBoolean pendingControlLogged = new AtomicBoolean(false);
        final AtomicLong firstRequestedAtMs = new AtomicLong(0);
        final AtomicBoolean graceWarned = new AtomicBoolean(false);
        final CountDownLatch stopLatch = new CountDownLatch(1);
        volatile boolean stopRequested;
        volatile boolean interruptDelivered;

        Registration(String sessionId, String replyId, boolean queued) {
            this.sessionId = sessionId;
            this.replyId = replyId;
            this.queued = queued;
        }
    }

    public void register(String sessionId, String replyId) {
        if (sessionId == null || replyId == null || replyId.isBlank()) {
            return;
        }
        var reg = new Registration(sessionId, replyId, false);
        active.put(replyId, reg);
        registry.bindTurn(sessionId, replyId);
        Long recentAt = recentStops.remove(replyId);
        if (recentAt != null && System.currentTimeMillis() - recentAt <= RECENT_STOP_TTL_MS) {
            dispatch(reg);
        }
    }

    public void registerQueued(String sessionId, String replyId) {
        if (sessionId == null || replyId == null || replyId.isBlank()) {
            return;
        }
        var reg = new Registration(sessionId, replyId, true);
        active.put(replyId, reg);
        Long recentAt = recentStops.remove(replyId);
        if (recentAt != null && System.currentTimeMillis() - recentAt <= RECENT_STOP_TTL_MS) {
            dispatch(reg);
        }
    }

    public boolean unregisterRegistrationOnly(String replyId) {
        if (replyId == null) {
            return false;
        }
        var reg = active.remove(replyId);
        return reg != null && (reg.stopRequested || reg.interruptDelivered);
    }

    public void unregister(String sessionId, String replyId) {
        if (replyId != null) {
            var reg = active.remove(replyId);
            if (reg != null) {
                reg.stopLatch.countDown();
            }
            recentStops.remove(replyId);
        }
        if (sessionId != null && replyId != null) {
            registry.unbindTurn(sessionId, replyId);
            stopStore.consume(sessionId, replyId);
        }
    }

    public boolean isStopRequested(String replyId) {
        var reg = replyId != null ? active.get(replyId) : null;
        return reg != null && (reg.stopRequested || reg.interruptDelivered);
    }

    public boolean awaitStop(String replyId, long timeoutMs) throws InterruptedException {
        var reg = replyId != null ? active.get(replyId) : null;
        if (reg == null) {
            if (timeoutMs > 0) {
                Thread.sleep(timeoutMs);
            }
            return false;
        }
        if (reg.stopRequested || reg.interruptDelivered) {
            return true;
        }
        if (timeoutMs <= 0) {
            return false;
        }
        return reg.stopLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void notifyLocal(String sessionId, String replyId) {
        if (!settings.enabled() || replyId == null || replyId.isBlank()) {
            return;
        }
        var reg = active.get(replyId);
        if (reg != null) {
            if (sessionId == null || sessionId.isBlank() || sessionId.equals(reg.sessionId)) {
                dispatch(reg);
            }
            return;
        }
        if (recentStops.size() >= MAX_RECENT_STOPS) {
            recentStops.clear();
        }
        recentStops.put(replyId, System.currentTimeMillis());
    }

    public void onControlRegistered(String canonicalSessionId) {
        if (!settings.enabled() || canonicalSessionId == null || canonicalSessionId.isBlank()) {
            return;
        }
        for (var reg : active.values()) {
            if (!reg.queued && reg.stopRequested && canonicalSessionId.equals(reg.sessionId)) {
                dispatch(reg);
            }
        }
    }

    @Scheduled(fixedDelayString = "#{@turnStopSettings.pollIntervalMs}",
               initialDelayString = "1000")
    public void poll() {
        if (!settings.enabled()) {
            return;
        }
        evictExpiredRecentStops();
        if (!active.isEmpty()) {
            try {
                var rows = stopStore.findAll();
                for (var row : rows) {
                    var reg = active.get(row.replyId());
                    if (reg != null) {
                        dispatch(reg);
                    }
                }
            } catch (Exception e) {
                log.warn("[turn-stop] poll failed: {}", e.getMessage());
            }
        }
        if (notifier != null) {
            try {
                notifier.ensureSubscribed();
            } catch (Exception ignored) {
            }
        }
    }

    private void dispatch(Registration reg) {
        reg.stopRequested = true;
        reg.stopLatch.countDown();
        if (reg.queued) {
            if (reg.deliveredLogged.compareAndSet(false, true)) {
                log.info("[turn-stop] stop requested for queued turn: sid={}, rid={}",
                    reg.sessionId, reg.replyId);
            }
            return;
        }
        long now = System.currentTimeMillis();
        reg.firstRequestedAtMs.compareAndSet(0, now);
        boolean sent = registry.interrupt(reg.sessionId, reg.replyId);
        if (sent) {
            reg.interruptDelivered = true;
            if (reg.deliveredLogged.compareAndSet(false, true)) {
                log.info("[turn-stop] interrupt delivered: sid={}, rid={}",
                    reg.sessionId, reg.replyId);
            }
        } else if (reg.pendingControlLogged.compareAndSet(false, true)) {
            log.warn("[turn-stop] stop requested but no local agent control registered yet "
                + "(sid={}, rid={}) — will retry on register or next tick",
                reg.sessionId, reg.replyId);
        }
        long firstAt = reg.firstRequestedAtMs.get();
        long waited = firstAt > 0 ? now - firstAt : 0;
        if (settings.graceMs() > 0 && waited >= settings.graceMs()
                && reg.graceWarned.compareAndSet(false, true)) {
            log.warn("[turn-stop] turn still running {}ms after stop request "
                + "(sid={}, rid={}) — likely blocked in a tool call; SDK interrupt "
                + "checkpoints only run between steps",
                waited, reg.sessionId, reg.replyId);
        }
    }

    private void evictExpiredRecentStops() {
        if (recentStops.isEmpty()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - RECENT_STOP_TTL_MS;
        recentStops.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    public int activeCount() {
        return active.size();
    }
}
