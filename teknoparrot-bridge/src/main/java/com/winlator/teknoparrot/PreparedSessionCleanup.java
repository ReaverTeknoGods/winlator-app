package com.winlator.teknoparrot;

import java.util.HashSet;
import java.util.Set;

/** Process-local ownership of prepared Activity cleanup, independent of weak UI references. */
public final class PreparedSessionCleanup {
    private static long nextGeneration;
    private static Ticket pending;

    private PreparedSessionCleanup() {
    }

    /** Reserve before posting startActivity so a stop cannot race Activity creation. */
    public static synchronized long reserve(String sessionId) {
        if (sessionId == null || !sessionId.matches("[0-9a-fA-F]{32}"))
            throw new IllegalArgumentException("A prepared session identity is required.");
        if (pending != null)
            throw new IllegalStateException("The previous prepared Activity cleanup is incomplete.");
        if (nextGeneration == Long.MAX_VALUE)
            throw new IllegalStateException("Prepared cleanup generation exhausted.");
        pending = new Ticket(sessionId, ++nextGeneration);
        return pending.generation;
    }

    /** Each Activity incarnation owns its own acknowledgment; an old instance cannot finish a new one. */
    public static synchronized Lease claim(String sessionId, long generation) {
        if (pending == null || generation != pending.generation ||
                !pending.sessionId.equals(sessionId))
            throw new IllegalStateException("The prepared Activity cleanup reservation is stale.");
        Lease lease = new Lease(pending);
        pending.claims.add(lease);
        pending.reserved = false;
        return lease;
    }

    /** Only a startActivity failure before any Activity claimed its reservation can cancel it. */
    public static synchronized void cancelUnclaimed(String sessionId, long generation) {
        if (pending != null && pending.generation == generation &&
                pending.sessionId.equals(sessionId) && pending.reserved &&
                pending.claims.isEmpty())
            pending = null;
    }

    /** Call only after successful resource and prefix cleanup, never from a timeout or failure path. */
    public static synchronized boolean complete(Lease lease, boolean recreating) {
        if (lease == null || pending != lease.ticket || !pending.claims.remove(lease))
            return false;
        // Android may create the replacement before or after destroying this
        // instance. Keep a reservation only when no replacement has claimed it.
        if (pending.claims.isEmpty()) {
            if (recreating) pending.reserved = true;
            else pending = null;
        }
        return true;
    }

    public static synchronized boolean isPending() {
        return pending != null;
    }

    public static final class Lease {
        private final Ticket ticket;

        private Lease(Ticket ticket) {
            this.ticket = ticket;
        }
    }

    private static final class Ticket {
        final String sessionId;
        final long generation;
        final Set<Lease> claims = new HashSet<>();
        boolean reserved = true;

        Ticket(String sessionId, long generation) {
            this.sessionId = sessionId;
            this.generation = generation;
        }
    }
}
