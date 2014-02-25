/*
 * Copyright 2012 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.appengine.tools.cloudstorage;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.appengine.tools.cloudstorage.RawGcsService.RawGcsCreationToken;
import com.google.apphosting.api.ApiProxy.ApiDeadlineExceededException;
import com.google.apphosting.api.ApiProxy.RPCFailedException;
import com.google.apphosting.api.ApiProxy.UnknownException;
import com.google.common.base.Throwables;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.Callable;

/**
 * Basic implementation of {@link GcsService}. Mostly delegates to {@link RawGcsService}
 */
final class GcsServiceImpl implements GcsService {

  private final RawGcsService raw;
  private final RetryParams retryParams;
  static final ExceptionHandler exceptionHandler = new ExceptionHandler.Builder()
      .retryOn(UnknownException.class, RPCFailedException.class, ApiDeadlineExceededException.class,
          IOException.class, SocketTimeoutException.class)
      .abortOn(InterruptedException.class, FileNotFoundException.class,
          MalformedURLException.class, ClosedByInterruptException.class,
          InterruptedIOException.class)
      .build();

  private static final int REQUEST_MAX_SIZE_BYTES = 10_000_000;
  private final int nonResumeableMaxSizeBytes;

  GcsServiceImpl(RawGcsService raw, RetryParams retryParams) {
    this.raw = checkNotNull(raw, "Null raw");
    this.retryParams = new RetryParams.Builder(retryParams).requestTimeoutRetryFactor(1.2).build();
    nonResumeableMaxSizeBytes = GcsOutputChannelImpl.getBufferSizeBytes(raw);
  }

  @Override
  public String toString() {
    return "GcsServiceImpl [retryParams=" + retryParams + "]";
  }

  @Override
  public GcsOutputChannel createOrReplace(
      final GcsFilename filename, final GcsFileOptions options) throws IOException {
    try {
      RawGcsCreationToken token = RetryHelper.runWithRetries(new Callable<RawGcsCreationToken>() {
        @Override
        public RawGcsCreationToken call() throws IOException {
          return raw.beginObjectCreation(
              filename, options, retryParams.getRequestTimeoutMillisForCurrentAttempt());
        }
      }, retryParams, exceptionHandler);
      return new GcsOutputChannelImpl(raw, token, retryParams);
    } catch (RetryInterruptedException ex) {
      throw new ClosedByInterruptException();
    } catch (NonRetriableException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw e;
    }
  }

  @Override
  public void createOrReplace(final GcsFilename filename, final GcsFileOptions options,
      final ByteBuffer src) throws IOException {
    if (src.remaining() > REQUEST_MAX_SIZE_BYTES) {
      @SuppressWarnings("resource")
      GcsOutputChannel channel = createOrReplace(filename, options);
      channel.write(src);
      channel.close();
      return;
    }

    try {
      RetryHelper.runWithRetries(new Callable<Void>() {
          @Override
          public Void call() throws IOException {
            raw.putObject(filename, options, src, retryParams.getRequestTimeoutMillis());
            return null;
          }
        }, retryParams, exceptionHandler);
    } catch (RetryInterruptedException ex) {
      throw new ClosedByInterruptException();
    } catch (NonRetriableException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw e;
    }
  }

  @Override
  public GcsInputChannel openReadChannel(GcsFilename filename, long startPosition) {
    return new SimpleGcsInputChannelImpl(raw, filename, startPosition, retryParams);
  }

  @Override
  public GcsInputChannel openPrefetchingReadChannel(
      GcsFilename filename, long startPosition, int blockSize) {
    return new PrefetchingGcsInputChannelImpl(
        raw, filename, blockSize, startPosition, retryParams);
  }

  @Override
  public GcsFileMetadata getMetadata(final GcsFilename filename) throws IOException {
    try {
      return RetryHelper.runWithRetries(new Callable<GcsFileMetadata>() {
        @Override
        public GcsFileMetadata call() throws IOException {
          return raw.getObjectMetadata(
              filename, retryParams.getRequestTimeoutMillisForCurrentAttempt());
        }
      }, retryParams, exceptionHandler);
    } catch (RetryInterruptedException ex) {
      throw new ClosedByInterruptException();
    } catch (NonRetriableException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw e;
    }
  }

  @Override
  public boolean delete(final GcsFilename filename) throws IOException {
    try {
      return RetryHelper.runWithRetries(new Callable<Boolean>() {
        @Override
        public Boolean call() throws IOException {
          return raw.deleteObject(filename, retryParams.getRequestTimeoutMillisForCurrentAttempt());
        }
      }, retryParams, exceptionHandler);
    } catch (RetryInterruptedException ex) {
      throw new ClosedByInterruptException();
    } catch (NonRetriableException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw e;
    }
  }
}
