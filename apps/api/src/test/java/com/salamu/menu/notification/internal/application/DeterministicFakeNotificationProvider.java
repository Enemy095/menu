package com.salamu.menu.notification.internal.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.salamu.menu.notification.api.NotificationChannel;

/** Test-only provider: deterministic, scriptable and incapable of network I/O. */
final class DeterministicFakeNotificationProvider implements NotificationProvider {

    private final NotificationChannel channel;
    private final Deque<Object> scripted = new ArrayDeque<>();
    private final List<ProviderRequest> requests = new ArrayList<>();

    DeterministicFakeNotificationProvider(NotificationChannel channel) {
        this.channel = channel;
    }

    DeterministicFakeNotificationProvider then(ProviderResult result) {
        scripted.addLast(result);
        return this;
    }

    DeterministicFakeNotificationProvider thenThrow(RuntimeException failure) {
        scripted.addLast(failure);
        return this;
    }

    List<ProviderRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public NotificationChannel channel() {
        return channel;
    }

    @Override
    public ProviderResult send(ProviderRequest request) {
        requests.add(request);
        Object outcome = scripted.removeFirst();
        if (outcome instanceof RuntimeException failure) {
            throw failure;
        }
        return (ProviderResult) outcome;
    }
}
