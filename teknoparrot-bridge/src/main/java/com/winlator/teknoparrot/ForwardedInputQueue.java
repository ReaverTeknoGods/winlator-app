package com.winlator.teknoparrot;

/**
 * Preallocated single-producer/single-consumer queue for the Winlator UI-thread
 * input tap. Publishing a slot is the release barrier; observing published is
 * the consumer's acquire barrier. A full queue is reported to the producer so
 * the session can request a state resynchronization instead of dropping edges.
 */
public final class ForwardedInputQueue {
    private final byte[][] slots;
    private final int[] lengths;
    private volatile long published;
    private volatile long consumed;
    private boolean writeAcquired;
    private boolean readAcquired;

    public ForwardedInputQueue(int capacity) {
        if (capacity < 2 || capacity > 4096)
            throw new IllegalArgumentException("TPI1 queue capacity must be between 2 and 4096");
        slots = new byte[capacity][];
        lengths = new int[capacity];
        for (int index = 0; index < capacity; index++)
            slots[index] = new byte[
                    ForwardedInputProtocol.HEADER_BYTES +
                    ForwardedInputProtocol.MAXIMUM_PAYLOAD_BYTES];
    }

    /** Called only by the input/UI producer thread. */
    public byte[] tryAcquireWriteBuffer() {
        if (writeAcquired)
            throw new IllegalStateException("A TPI1 producer slot is already acquired");
        long currentPublished = published;
        if (currentPublished - consumed >= slots.length)
            return null;
        writeAcquired = true;
        return slots[indexOf(currentPublished)];
    }

    /** Called only by the input/UI producer thread. */
    public void publishWrite(int length) {
        if (!writeAcquired)
            throw new IllegalStateException("No TPI1 producer slot is acquired");
        if (length < ForwardedInputProtocol.HEADER_BYTES || length > slots[0].length)
            throw new IllegalArgumentException("TPI1 frame length is outside the queue slot");
        long currentPublished = published;
        lengths[indexOf(currentPublished)] = length;
        writeAcquired = false;
        published = currentPublished + 1;
    }

    /** Called only by the input/UI producer thread after an encoder failure. */
    public void cancelWrite() {
        if (!writeAcquired)
            throw new IllegalStateException("No TPI1 producer slot is acquired");
        writeAcquired = false;
    }

    /** Called only by the socket consumer thread. */
    public byte[] tryAcquireReadBuffer() {
        if (readAcquired)
            throw new IllegalStateException("A TPI1 consumer slot is already acquired");
        long currentConsumed = consumed;
        if (currentConsumed == published)
            return null;
        readAcquired = true;
        return slots[indexOf(currentConsumed)];
    }

    /** Called only by the socket consumer thread while a slot is acquired. */
    public int acquiredReadLength() {
        if (!readAcquired)
            throw new IllegalStateException("No TPI1 consumer slot is acquired");
        return lengths[indexOf(consumed)];
    }

    /** Called only by the socket consumer thread after the complete frame is sent. */
    public void releaseRead() {
        if (!readAcquired)
            throw new IllegalStateException("No TPI1 consumer slot is acquired");
        long currentConsumed = consumed;
        readAcquired = false;
        consumed = currentConsumed + 1;
    }

    public int capacity() {
        return slots.length;
    }

    public int size() {
        long size = published - consumed;
        if (size <= 0)
            return 0;
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)size;
    }

    private int indexOf(long cursor) {
        return (int)(cursor % slots.length);
    }
}
