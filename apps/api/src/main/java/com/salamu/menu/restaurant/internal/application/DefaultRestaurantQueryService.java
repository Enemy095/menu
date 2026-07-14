package com.salamu.menu.restaurant.internal.application;

import java.util.List;
import java.util.Objects;

import com.salamu.menu.restaurant.api.DefaultRestaurantQuery;
import com.salamu.menu.restaurant.api.DefaultRestaurantUnavailableException;
import com.salamu.menu.restaurant.api.RestaurantSnapshot;
import com.salamu.menu.restaurant.api.RestaurantStatus;

import org.springframework.stereotype.Service;

@Service
public class DefaultRestaurantQueryService implements DefaultRestaurantQuery {

    private final DefaultRestaurantRepository repository;

    public DefaultRestaurantQueryService(DefaultRestaurantRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public RestaurantSnapshot requireActiveDefault() {
        try {
            List<DefaultRestaurantRepository.StoredRestaurant> matches = repository.findActiveDefaults();
            if (matches == null || matches.size() != 1 || matches.getFirst() == null) {
                throw new DefaultRestaurantUnavailableException();
            }
            var stored = matches.getFirst();
            if (stored.status() != RestaurantStatus.ACTIVE) {
                throw new DefaultRestaurantUnavailableException();
            }
            return new RestaurantSnapshot(
                    stored.id(), stored.externalKey(), stored.displayName(), stored.status());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new DefaultRestaurantUnavailableException();
        }
    }
}
