package com.salamu.menu.notification.internal.infrastructure.telegram;

import java.time.Duration;

interface TelegramBotApi {

    SendResult sendHtmlMessage(String text, Duration timeout);

    record SendResult(Outcome outcome, String messageId, String errorCode) {

        enum Outcome {
            SUCCEEDED,
            TRANSIENT_FAILURE,
            PERMANENT_FAILURE
        }

        static SendResult succeeded(String messageId) {
            return new SendResult(Outcome.SUCCEEDED, messageId, null);
        }

        static SendResult transientFailure(String errorCode) {
            return new SendResult(Outcome.TRANSIENT_FAILURE, null, errorCode);
        }

        static SendResult permanentFailure(String errorCode) {
            return new SendResult(Outcome.PERMANENT_FAILURE, null, errorCode);
        }
    }
}
