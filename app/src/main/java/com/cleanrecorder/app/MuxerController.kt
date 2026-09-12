package com.cleanrecorder.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * MediaMuxer requires ALL tracks to be added via addTrack() before start() is called, and
 * no writeSampleData() call is legal before start(). Video and audio run on independent
 * encoder/drain threads with independent timelines, so this controller gates both behind
 * a CountDownLatch keyed to the expected track count.
 */
class MuxerController(
    outputFd: java.io.FileDescriptor,
    private val expectedTrackCount: Int
) {
    private val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val startLatch = CountDownLatch(1)
    private val tracksAdded = AtomicInteger(0)
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val lock = Any()

    /** Registers a track and returns its muxer track index. Starts the muxer once all
     * expected tracks are present. Safe to call concurrently from multiple threads. */
    fun addTrack(format: MediaFormat): Int {
        synchronized(lock) {
            val index = muxer.addTrack(format)
            val nowAdded = tracksAdded.incrementAndGet()
            if (nowAdded >= expectedTrackCount && started.compareAndSet(false, true)) {
                muxer.start()
                startLatch.countDown()
            }
            return index
        }
    }

    /** Blocks the calling drain thread until the muxer has started, then writes the sample. */
    fun writeSampleData(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped.get()) return
        startLatch.await()
        synchronized(lock) {
            if (!stopped.get()) {
                muxer.writeSampleData(trackIndex, buffer, info)
            }
        }
    }

    fun isStarted(): Boolean = started.get()

    fun stopAndRelease() {
        synchronized(lock) {
            if (stopped.compareAndSet(false, true)) {
                // If we never actually started (e.g. audio track never appeared because
                // the source failed), release without calling stop() to avoid an IllegalStateException.
                if (started.get()) {
                    try {
                        muxer.stop()
                    } catch (_: Exception) {
                        // Stopping with zero samples written on a track throws; nothing more
                        // we can do at teardown time, the file may just lack that track.
                    }
                }
                try {
                    muxer.release()
                } catch (_: Exception) {
                }
                startLatch.countDown()
            }
        }
    }
}
