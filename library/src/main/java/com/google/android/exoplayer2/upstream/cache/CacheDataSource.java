/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.upstream.cache;

import android.net.Uri;
import android.support.annotation.IntDef;
import android.util.Log;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSink;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSourceException;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.TeeDataSource;
import com.google.android.exoplayer2.upstream.cache.CacheDataSink.CacheDataSinkException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A {@link DataSource} that reads and writes a {@link Cache}. Requests are fulfilled from the cache
 * when possible. When data is not cached it is requested from an upstream {@link DataSource} and
 * written into the cache.
 */
public final class CacheDataSource implements DataSource {

  /**
   * Default maximum single cache file size.
   *
   * @see #CacheDataSource(Cache, DataSource, int)
   * @see #CacheDataSource(Cache, DataSource, int, long)
   */
  public static final long DEFAULT_MAX_CACHE_FILE_SIZE = 2 * 1024 * 1024;

  /**
   * Flags controlling the cache's behavior.
   */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef(flag = true, value = {FLAG_BLOCK_ON_CACHE, FLAG_IGNORE_CACHE_ON_ERROR,
      FLAG_CACHE_UNBOUNDED_REQUESTS})
  public @interface Flags {}
  /**
   * A flag indicating whether we will block reads if the cache key is locked. If this flag is
   * set, then we will read from upstream if the cache key is locked.
   */
  public static final int FLAG_BLOCK_ON_CACHE = 1 << 0;

  /**
   * A flag indicating whether the cache is bypassed following any cache related error. If set
   * then cache related exceptions may be thrown for one cycle of open, read and close calls.
   * Subsequent cycles of these calls will then bypass the cache.
   */
  public static final int FLAG_IGNORE_CACHE_ON_ERROR = 1 << 1;

  /**
   * A flag indicating whether the response is cached if the range of the request is unbounded.
   * Disabled by default because, as a side effect, this may allow streams with every chunk from a
   * separate URL cached which is broken currently.
   */
  public static final int FLAG_CACHE_UNBOUNDED_REQUESTS = 1 << 2;

  /**
   * Listener of {@link CacheDataSource} events.
   */
  public interface EventListener {

    /**
     * Called when bytes have been read from the cache.
     *
     * @param cacheSizeBytes Current cache size in bytes.
     * @param cachedBytesRead Total bytes read from the cache since this method was last called.
     */
    void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead);

  }

  private static final String TAG = "CacheDataSource";

  private final Cache cache;
  private final DataSource cacheReadDataSource;
  private final DataSource cacheWriteDataSource;
  private final DataSource upstreamDataSource;
  private final EventListener eventListener;

  private final boolean blockOnCache;
  private final boolean ignoreCacheOnError;
  private final boolean bypassUnboundedRequests;

  private DataSource currentDataSource;
  private boolean currentRequestUnbounded;
  private Uri uri;
  private int flags;
  private String key;
  private long readPosition;
  private long bytesRemaining;
  private CacheSpan lockedSpan;
  private boolean seenCacheError;
  private boolean currentRequestIgnoresCache;
  private long totalCachedBytesRead;

  /**
   * Constructs an instance with default {@link DataSource} and {@link DataSink} instances for
   * reading and writing the cache and with {@link #DEFAULT_MAX_CACHE_FILE_SIZE}.
   */
  public CacheDataSource(Cache cache, DataSource upstream, @Flags int flags) {
    this(cache, upstream, flags, DEFAULT_MAX_CACHE_FILE_SIZE);
  }

  /**
   * Constructs an instance with default {@link DataSource} and {@link DataSink} instances for
   * reading and writing the cache. The sink is configured to fragment data such that no single
   * cache file is greater than maxCacheFileSize bytes.
   *
   * @param cache The cache.
   * @param upstream A {@link DataSource} for reading data not in the cache.
   * @param flags A combination of {@link #FLAG_BLOCK_ON_CACHE}, {@link #FLAG_IGNORE_CACHE_ON_ERROR}
   *     and {@link #FLAG_CACHE_UNBOUNDED_REQUESTS} or 0.
   * @param maxCacheFileSize The maximum size of a cache file, in bytes. If the cached data size
   *     exceeds this value, then the data will be fragmented into multiple cache files. The
   *     finer-grained this is the finer-grained the eviction policy can be.
   */
  public CacheDataSource(Cache cache, DataSource upstream, @Flags int flags,
      long maxCacheFileSize) {
    this(cache, upstream, new FileDataSource(), new CacheDataSink(cache, maxCacheFileSize),
        flags, null);
  }

  /**
   * Constructs an instance with arbitrary {@link DataSource} and {@link DataSink} instances for
   * reading and writing the cache. One use of this constructor is to allow data to be transformed
   * before it is written to disk.
   *
   * @param cache The cache.
   * @param upstream A {@link DataSource} for reading data not in the cache.
   * @param cacheReadDataSource A {@link DataSource} for reading data from the cache.
   * @param cacheWriteDataSink A {@link DataSink} for writing data to the cache.
   * @param flags A combination of {@link #FLAG_BLOCK_ON_CACHE}, {@link #FLAG_IGNORE_CACHE_ON_ERROR}
   *     and {@link #FLAG_CACHE_UNBOUNDED_REQUESTS} or 0.
   * @param eventListener An optional {@link EventListener} to receive events.
   */
  public CacheDataSource(Cache cache, DataSource upstream, DataSource cacheReadDataSource,
      DataSink cacheWriteDataSink, @Flags int flags, EventListener eventListener) {
    this.cache = cache;
    this.cacheReadDataSource = cacheReadDataSource;
    this.blockOnCache = (flags & FLAG_BLOCK_ON_CACHE) != 0;
    this.ignoreCacheOnError = (flags & FLAG_IGNORE_CACHE_ON_ERROR) != 0;
    this.bypassUnboundedRequests = (flags & FLAG_CACHE_UNBOUNDED_REQUESTS) == 0;
    this.upstreamDataSource = upstream;
    if (cacheWriteDataSink != null) {
      this.cacheWriteDataSource = new TeeDataSource(upstream, cacheWriteDataSink);
    } else {
      this.cacheWriteDataSource = null;
    }
    this.eventListener = eventListener;
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    try {
      uri = dataSpec.uri;
      flags = dataSpec.flags;
      key = dataSpec.key;
      readPosition = dataSpec.position;
      currentRequestIgnoresCache = (ignoreCacheOnError && seenCacheError)
          || (bypassUnboundedRequests && dataSpec.length == C.LENGTH_UNSET);
      if (dataSpec.length != C.LENGTH_UNSET || currentRequestIgnoresCache) {
        bytesRemaining = dataSpec.length;
      } else {
        bytesRemaining = cache.getContentLength(key);
        if (bytesRemaining != C.LENGTH_UNSET) {
          bytesRemaining -= dataSpec.position;
        }
      }
      openNextSource(true);
      return bytesRemaining;
    } catch (IOException e) {
      handleBeforeThrow(e);
      throw e;
    }
  }

  @Override
  public int read(byte[] buffer, int offset, int readLength) throws IOException {
    if (readLength == 0) {
      return 0;
    }
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    try {
      int bytesRead = currentDataSource.read(buffer, offset, readLength);
      if (bytesRead >= 0) {
        if (currentDataSource == cacheReadDataSource) {
          totalCachedBytesRead += bytesRead;
        }
        readPosition += bytesRead;
        if (bytesRemaining != C.LENGTH_UNSET) {
          bytesRemaining -= bytesRead;
        }
      } else {
        if (currentRequestUnbounded) {
          // We only do unbounded requests to upstream and only when we don't know the actual stream
          // length. So we reached the end of stream.
          setContentLength(readPosition);
          bytesRemaining = 0;
        }
        closeCurrentSource();
        if (bytesRemaining > 0 || bytesRemaining == C.LENGTH_UNSET) {
          if (openNextSource(false)) {
            return read(buffer, offset, readLength);
          }
        }
      }
      return bytesRead;
    } catch (IOException e) {
      handleBeforeThrow(e);
      throw e;
    }
  }

  @Override
  public Uri getUri() {
    return currentDataSource == upstreamDataSource ? currentDataSource.getUri() : uri;
  }

  @Override
  public void close() throws IOException {
    uri = null;
    notifyBytesRead();
    try {
      closeCurrentSource();
    } catch (IOException e) {
      handleBeforeThrow(e);
      throw e;
    }
  }

  /**
   * Opens the next source. If the cache contains data spanning the current read position then
   * {@link #cacheReadDataSource} is opened to read from it. Else {@link #upstreamDataSource} is
   * opened to read from the upstream source and write into the cache.
   * @param initial Whether it is the initial open call.
   */
  private boolean openNextSource(boolean initial) throws IOException {
    DataSpec dataSpec;
    CacheSpan span;
    if (currentRequestIgnoresCache) {
      span = null;
    } else if (blockOnCache) {
      try {
        span = cache.startReadWrite(key, readPosition);
      } catch (InterruptedException e) {
        throw new InterruptedIOException();
      }
    } else {
      span = cache.startReadWriteNonBlocking(key, readPosition);
    }

    if (span == null) {
      // The data is locked in the cache, or we're ignoring the cache. Bypass the cache and read
      // from upstream.
      currentDataSource = upstreamDataSource;
      dataSpec = new DataSpec(uri, readPosition, bytesRemaining, key, flags);
    } else if (span.isCached) {
      // Data is cached, read from cache.
      Uri fileUri = Uri.fromFile(span.file);
      long filePosition = readPosition - span.position;
      long length = span.length - filePosition;
      if (bytesRemaining != C.LENGTH_UNSET) {
        length = Math.min(length, bytesRemaining);
      }
      dataSpec = new DataSpec(fileUri, readPosition, filePosition, length, key, flags);
      currentDataSource = cacheReadDataSource;
    } else {
      // Data is not cached, and data is not locked, read from upstream with cache backing.
      lockedSpan = span;
      long length;
      if (span.isOpenEnded()) {
        length = bytesRemaining;
      } else {
        length = span.length;
        if (bytesRemaining != C.LENGTH_UNSET) {
          length = Math.min(length, bytesRemaining);
        }
      }
      dataSpec = new DataSpec(uri, readPosition, length, key, flags);
      currentDataSource = cacheWriteDataSource != null ? cacheWriteDataSource
          : upstreamDataSource;
    }

    currentRequestUnbounded = dataSpec.length == C.LENGTH_UNSET;
    boolean successful = false;
    long currentBytesRemaining = 0;
    try {
      currentBytesRemaining = currentDataSource.open(dataSpec);
      successful = true;
    } catch (IOException e) {
      // if this isn't the initial open call (we had read some bytes) and an unbounded range request
      // failed because of POSITION_OUT_OF_RANGE then mute the exception. We are trying to find the
      // end of the stream.
      if (!initial && currentRequestUnbounded) {
        Throwable cause = e;
        while (cause != null) {
          if (cause instanceof DataSourceException) {
            int reason = ((DataSourceException) cause).reason;
            if (reason == DataSourceException.POSITION_OUT_OF_RANGE) {
              e = null;
              break;
            }
          }
          cause = cause.getCause();
        }
      }
      if (e != null) {
        throw e;
      }
    }

    // If we did an unbounded request (which means it's to upstream and
    // bytesRemaining == C.LENGTH_UNSET) and got a resolved length from open() request
    if (currentRequestUnbounded && currentBytesRemaining != C.LENGTH_UNSET) {
      bytesRemaining = currentBytesRemaining;
      // If writing into cache
      if (lockedSpan != null) {
        setContentLength(dataSpec.position + bytesRemaining);
      }
    }
    return successful;
  }

  private void setContentLength(long length) {
    if (!cache.setContentLength(key, length)) {
      Log.e(TAG, "cache.setContentLength(" + length + ") failed. cache.getContentLength() = "
          + cache.getContentLength(key));
    }
  }

  private void closeCurrentSource() throws IOException {
    if (currentDataSource == null) {
      return;
    }
    try {
      currentDataSource.close();
      currentDataSource = null;
      currentRequestUnbounded = false;
    } finally {
      if (lockedSpan != null) {
        cache.releaseHoleSpan(lockedSpan);
        lockedSpan = null;
      }
    }
  }

  private void handleBeforeThrow(IOException exception) {
    if (currentDataSource == cacheReadDataSource || exception instanceof CacheDataSinkException) {
      seenCacheError = true;
    }
  }

  private void notifyBytesRead() {
    if (eventListener != null && totalCachedBytesRead > 0) {
      eventListener.onCachedBytesRead(cache.getCacheSpace(), totalCachedBytesRead);
      totalCachedBytesRead = 0;
    }
  }

}
