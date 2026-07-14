package com.salamu.menu.menu.api;

/** Locks and verifies the selected published menu for the duration of the caller transaction. */
public interface CurrentPublishedMenuGuard {

    boolean lockCurrent(MenuVersionId menuVersionId, long version);
}
