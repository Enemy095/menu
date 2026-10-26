package com.salamu.menu.notification.internal.infrastructure.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.telegram-calendar", name = "enabled", havingValue = "true")
class TelegramCalendarPoller {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TelegramCalendarPoller.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TelegramCalendarGateway bot;
    private final TelegramCalendarUpdateHandler handler;
    private long nextWarningNanos;
    TelegramCalendarPoller(JdbcTemplate jdbc, PlatformTransactionManager transactions, TelegramCalendarGateway bot, TelegramCalendarUpdateHandler handler) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(transactions); this.bot = bot; this.handler = handler;
    }
    @Scheduled(fixedDelayString = "${app.telegram-calendar.poll-interval:1500}")
    public void poll() {
        try {
            transaction.executeWithoutResult(status -> {
                boolean locked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(1835363957, 2)", Boolean.class));
                if (!locked) return;
                var cursors = jdbc.query("SELECT last_update_id FROM telegram_calendar_poll_state WHERE singleton_id = 1", (rs, row) -> rs.getLong(1));
                if (cursors.isEmpty()) {
                    // Do not execute commands queued before owner access was configured.
                    var last = bot.updates(-1);
                    long cursor = 0;
                    for (var update : last) cursor = Math.max(cursor, update.path("update_id").asLong(0));
                    jdbc.update("INSERT INTO telegram_calendar_poll_state(singleton_id,last_update_id) VALUES (1,?)", cursor);
                    return;
                }
                long cursor = cursors.getFirst();
                for (var update : bot.updates(cursor + 1)) {
                    long updateId = update.path("update_id").asLong(0);
                    if (updateId <= cursor) continue;
                    handler.handle(update);
                    cursor = updateId;
                    jdbc.update("UPDATE telegram_calendar_poll_state SET last_update_id = ? WHERE singleton_id = 1", cursor);
                    // Commit each handled update separately: a later command failure must
                    // not roll back the cursor for replies already sent to Telegram.
                    break;
                }
            });
        } catch (RuntimeException failure) {
            long now = System.nanoTime();
            if (now >= nextWarningNanos) {
                LOG.warn("Telegram calendar poll failed; retry scheduled");
                nextWarningNanos = now + java.time.Duration.ofMinutes(1).toNanos();
            }
        }
    }
}
