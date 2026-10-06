package org.siloserver.silo.util

/**
 * Current wall-clock time in epoch milliseconds. Room clock sampling compares
 * it with server timestamps, which are wall-clock instants; local scheduling
 * uses a monotonic source instead.
 */
expect fun wallClockMillis(): Long
