// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.collect.nestedset;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * Supports association between fingerprints and NestedSet contents. A single NestedSetStore
 * instance should be globally available across a single process.
 *
 * <p>Maintains the fingerprint -> contents side of the bimap by decomposing nested Object[]'s.
 *
 * <p>For example, suppose the NestedSet A can be drawn as:
 *
 * <pre>
 *         A
 *       /  \
 *      B   C
 *     / \
 *    D  E
 * </pre>
 *
 * <p>Then, in memory, A = [[D, E], C]. To store the NestedSet, we would rely on the fingerprint
 * value FPb = fingerprint([D, E]) and write
 *
 * <pre>{@code A -> fingerprint(FPb, C)}</pre>
 *
 * <p>On retrieval, A will be reconstructed by first retrieving A using its fingerprint, and then
 * recursively retrieving B using its fingerprint.
 */
public class NestedSetStore {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final Duration FETCH_FROM_STORAGE_LOGGING_THRESHOLD = Duration.ofSeconds(5);

  /**
   * Exception indicating that {@link NestedSetStorageEndpoint#get} was called with a fingerprint
   * that does not exist in the store.
   */
  public static final class MissingNestedSetException extends Exception {

    public MissingNestedSetException(ByteString fingerprint) {
      this(fingerprint, /*cause=*/ null);
    }

    public MissingNestedSetException(ByteString fingerprint, @Nullable Throwable cause) {
      super("No NestedSet data for " + fingerprint, cause);
    }
  }

  /** Stores fingerprint -> NestedSet associations. */
  public interface NestedSetStorageEndpoint {
    /**
     * Associates a fingerprint with the serialized representation of some NestedSet contents.
     * Returns a future that completes when the write completes.
     *
     * <p>It is the responsibility of the caller to deduplicate {@code put} calls, to avoid multiple
     * writes of the same fingerprint.
     */
    ListenableFuture<Void> put(ByteString fingerprint, byte[] serializedBytes) throws IOException;

    /**
     * Retrieves the serialized bytes for the NestedSet contents associated with this fingerprint.
     *
     * <p>If the given fingerprint does not exist in the store, the returned future fails with a
     * {@link MissingNestedSetException}.
     *
     * <p>It is the responsibility of the caller to deduplicate {@code get} calls, to avoid multiple
     * fetches of the same fingerprint.
     */
    ListenableFuture<byte[]> get(ByteString fingerprint) throws IOException;
  }

  /** An in-memory {@link NestedSetStorageEndpoint} */
  @VisibleForTesting
  public static class InMemoryNestedSetStorageEndpoint implements NestedSetStorageEndpoint {
    private final ConcurrentHashMap<ByteString, byte[]> fingerprintToContents =
        new ConcurrentHashMap<>();

    @Override
    public ListenableFuture<Void> put(ByteString fingerprint, byte[] serializedBytes) {
      fingerprintToContents.put(fingerprint, serializedBytes);
      return immediateFuture(null);
    }

    @Override
    public ListenableFuture<byte[]> get(ByteString fingerprint) {
      return immediateFuture(fingerprintToContents.get(fingerprint));
    }
  }

  /** The result of a fingerprint computation, including the status of its storage. */
  @AutoValue
  abstract static class FingerprintComputationResult {
    static FingerprintComputationResult create(
        ByteString fingerprint, ListenableFuture<Void> writeStatus) {
      return new AutoValue_NestedSetStore_FingerprintComputationResult(fingerprint, writeStatus);
    }

    abstract ByteString fingerprint();

    abstract ListenableFuture<Void> writeStatus();
  }

  private final NestedSetSerializationCache nestedSetCache;
  private final NestedSetStorageEndpoint endpoint;
  private final Executor executor;

  /** Creates a NestedSetStore with the provided {@link NestedSetStorageEndpoint} as a backend. */
  @VisibleForTesting
  public NestedSetStore(NestedSetStorageEndpoint endpoint) {
    this(endpoint, directExecutor(), BugReporter.defaultInstance());
  }

  /**
   * Creates a NestedSetStore with the provided {@link NestedSetStorageEndpoint} and executor for
   * deserialization.
   */
  public NestedSetStore(
      NestedSetStorageEndpoint endpoint, Executor executor, BugReporter bugReporter) {
    this(endpoint, new NestedSetSerializationCache(bugReporter), executor);
  }

  @VisibleForTesting
  NestedSetStore(
      NestedSetStorageEndpoint endpoint,
      NestedSetSerializationCache nestedSetCache,
      Executor executor) {
    this.endpoint = checkNotNull(endpoint);
    this.nestedSetCache = checkNotNull(nestedSetCache);
    this.executor = checkNotNull(executor);
  }

  /** Creates a NestedSetStore with an in-memory storage backend. */
  public static NestedSetStore inMemory() {
    return new NestedSetStore(new InMemoryNestedSetStorageEndpoint());
  }

  /**
   * Computes and returns the fingerprint for the given {@link NestedSet} contents using the given
   * {@link SerializationContext}, while also associating the contents with the computed fingerprint
   * in the store. Recursively does the same for all transitive {@code Object[]} members of the
   * provided contents.
   *
   * <p>We wish to compute a fingerprint for each array only once. However, this is not currently
   * enforced, due to the check-then-act race below, where we check {@link
   * NestedSetSerializationCache#fingerprintForContents} and then, significantly later, call {@link
   * NestedSetSerializationCache#putIfAbsent}. It is not straightforward to solve this with a
   * typical cache loader because the fingerprint computation is recursive, and cache loaders must
   * not attempt to update the cache while loading a result. Even if we duplicate fingerprint
   * computation, only one thread will end up calling {@link NestedSetStorageEndpoint#put} (the one
   * that wins the race to {@link NestedSetSerializationCache#putIfAbsent}).
   */
  FingerprintComputationResult computeFingerprintAndStore(
      Object[] contents, SerializationContext serializationContext)
      throws SerializationException, IOException {
    FingerprintComputationResult priorFingerprint = nestedSetCache.fingerprintForContents(contents);
    if (priorFingerprint != null) {
      return priorFingerprint;
    }

    // For every fingerprint computation, we need to use a new memoization table.  This is required
    // to guarantee that the same child will always have the same fingerprint - otherwise,
    // differences in memoization context could cause part of a child to be memoized in one
    // fingerprinting but not in the other.  We expect this clearing of memoization state to be a
    // major source of extra work over the naive serialization approach.  The same value may have to
    // be serialized many times across separate fingerprintings.
    SerializationContext newSerializationContext = serializationContext.getNewMemoizingContext();
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(byteArrayOutputStream);

    ImmutableList.Builder<ListenableFuture<Void>> futureBuilder = ImmutableList.builder();
    try {
      codedOutputStream.writeInt32NoTag(contents.length);
      for (Object child : contents) {
        if (child instanceof Object[]) {
          FingerprintComputationResult fingerprintComputationResult =
              computeFingerprintAndStore((Object[]) child, serializationContext);
          futureBuilder.add(fingerprintComputationResult.writeStatus());
          newSerializationContext.serialize(
              fingerprintComputationResult.fingerprint(), codedOutputStream);
        } else {
          newSerializationContext.serialize(child, codedOutputStream);
        }
      }
      codedOutputStream.flush();
    } catch (IOException e) {
      throw new SerializationException("Could not serialize NestedSet contents", e);
    }

    byte[] serializedBytes = byteArrayOutputStream.toByteArray();
    ByteString fingerprint =
        ByteString.copyFrom(Hashing.md5().hashBytes(serializedBytes).asBytes());
    SettableFuture<Void> localWriteFuture = SettableFuture.create();
    futureBuilder.add(localWriteFuture);

    // If this is a NestedSet<NestedSet>, serialization of the contents will itself have writes.
    ListenableFuture<Void> innerWriteFutures =
        newSerializationContext.createFutureToBlockWritingOn();
    if (innerWriteFutures != null) {
      futureBuilder.add(innerWriteFutures);
    }

    ListenableFuture<Void> writeFuture =
        Futures.whenAllComplete(futureBuilder.build()).call(() -> null, directExecutor());
    FingerprintComputationResult result =
        FingerprintComputationResult.create(fingerprint, writeFuture);

    // TODO(b/202438580): Pass through relevant context.
    FingerprintComputationResult existingResult =
        nestedSetCache.putIfAbsent(contents, result, /*context=*/ "");
    if (existingResult != null) {
      return existingResult; // Another thread won the fingerprint computation race.
    }

    // This fingerprint was not cached previously, so we must ensure that it is written to storage.
    localWriteFuture.setFuture(endpoint.put(fingerprint, serializedBytes));
    return result;
  }

  @SuppressWarnings("unchecked")
  private static ListenableFuture<Object[]> maybeWrapInFuture(Object contents) {
    if (contents instanceof Object[]) {
      return immediateFuture((Object[]) contents);
    }
    return (ListenableFuture<Object[]>) contents;
  }

  /**
   * Retrieves and deserializes the NestedSet contents associated with the given fingerprint.
   *
   * <p>We wish to only do one deserialization per fingerprint. This is enforced by the {@link
   * #nestedSetCache}, which is responsible for returning the actual contents or the canonical
   * future that will contain the results of the deserialization. If that future is not owned by the
   * current call of this method, it doesn't have to do anything further.
   *
   * <p>The return value is either an {@code Object[]} or a {@code ListenableFuture<Object[]>},
   * which may be completed with a {@link MissingNestedSetException}.
   */
  // All callers will test on type and check return value if it's a future.
  @SuppressWarnings("FutureReturnValueIgnored")
  Object getContentsAndDeserialize(
      ByteString fingerprint, DeserializationContext deserializationContext) throws IOException {
    SettableFuture<Object[]> future = SettableFuture.create();
    // TODO(b/202438580): Pass through relevant context.
    Object contents = nestedSetCache.putFutureIfAbsent(fingerprint, future, /*context=*/ "");
    if (contents != null) {
      return contents;
    }
    ListenableFuture<byte[]> retrieved = endpoint.get(fingerprint);
    Stopwatch fetchStopwatch = Stopwatch.createStarted();
    future.setFuture(
        Futures.transformAsync(
            retrieved,
            bytes -> {
              Duration fetchDuration = fetchStopwatch.elapsed();
              if (FETCH_FROM_STORAGE_LOGGING_THRESHOLD.compareTo(fetchDuration) < 0) {
                logger.atInfo().log(
                    "NestedSet fetch took: %dms, size: %dB",
                    fetchDuration.toMillis(), bytes.length);
              }

              CodedInputStream codedIn = CodedInputStream.newInstance(bytes);
              int numberOfElements = codedIn.readInt32();
              DeserializationContext newDeserializationContext =
                  deserializationContext.getNewMemoizingContext();

              // The elements of this list are futures for the deserialized values of these
              // NestedSet contents.  For direct members, the futures complete immediately and yield
              // an Object.  For transitive members (fingerprints), the futures complete with the
              // underlying fetch, and yield Object[]s.
              List<ListenableFuture<?>> deserializationFutures = new ArrayList<>();
              for (int i = 0; i < numberOfElements; i++) {
                Object deserializedElement = newDeserializationContext.deserialize(codedIn);
                if (deserializedElement instanceof ByteString) {
                  deserializationFutures.add(
                      maybeWrapInFuture(
                          getContentsAndDeserialize(
                              (ByteString) deserializedElement, deserializationContext)));
                } else {
                  deserializationFutures.add(Futures.immediateFuture(deserializedElement));
                }
              }

              return Futures.whenAllComplete(deserializationFutures)
                  .call(
                      () -> {
                        Object[] deserializedContents = new Object[deserializationFutures.size()];
                        for (int i = 0; i < deserializationFutures.size(); i++) {
                          deserializedContents[i] = Futures.getDone(deserializationFutures.get(i));
                        }
                        return deserializedContents;
                      },
                      executor);
            },
            executor));
    return future;
  }
}
