/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import spray.caching.Cache
import spray.caching.LruCache
import spray.caching.ValueMagnet.fromAny
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId

/**
 * A cache that allows multiple readers, but only a single writer, at
 * a time. It will make a best effort attempt to coalesce reads, but
 * does not guarantee that all overlapping reads will be coalesced.
 *
 * The cache operates by bracketing all reads and writes. A read
 * imposes a lightweight read lock, by inserting an entry into the
 * cache with State.ReadInProgress. A write does the same, with
 * State.WriteInProgress.
 *
 * On read or write completion, the value transitions to State.Cached.
 *
 * State.Initial is represented implicitly by absence from the cache.
 *
 * The handshake for cache entry state transition is:
 *
 * 1. if entry is in an agreeable state, proceed
 *      where agreeable for reads is Initial, ReadInProgress, or Cached
 *                    and the read proceeds by ensuring the entry is State.ReadInProgress
 *      and agreeable for writes is Initial or Cached
 *                    and the write proceeds by ensuring the entry is State.WriteInProgress
 *      and agreeable for deletes is Cached
 *
 * 2. if entry is not in an agreeable state, then read- or write-around the cache;
 *    for deletions, we allow the delete to proceed, and mark the entry as InvalidateWhenDone
 *    the owning reader or writer is then responsible for ensuring that the entry is invalid
 *    when that read or write completes
 *
 * 3. to swap in the new state to an existing entry, we use an AtomicReference.compareAndSet
 *
 * 4. only if the db operation completes with success, atomically set the state to Cached.
 *
 * 5. lastly, for cache invalidations that race with, we mark the entry as
 *
 */
private object MultipleReadersSingleWriterCache {
    /** Each entry has a state, as explained in the class comment above. */
    object State extends Enumeration {
        type State = Value
        val ReadInProgress, WriteInProgress, InvalidateInProgress, InvalidateWhenDone, Cached = Value
    }

    import State._

    /** Failure modes, which will only occur if there is a bug in this implementation */
    case class ConcurrentOperationUnderRead(actualState: State) extends Exception(s"Cache read started, but completion raced with a concurrent operation: $actualState.")
    case class ConcurrentOperationUnderUpdate(actualState: State) extends Exception(s"Cache update started, but completion raced with a concurrent operation: $actualState.")
    case class SquashedInvalidation(actualState: State) extends Exception(s"Cache invalidation squashed due $actualState.")
    case class StaleRead(actualState: State) extends Exception(s"Attempted read of invalid entry due to $actualState.")
}

trait MultipleReadersSingleWriterCache[W, Winfo] {
    import MultipleReadersSingleWriterCache._
    import MultipleReadersSingleWriterCache.State._

    /** Subclasses: Toggle this to enable/disable caching for your entity type. */
    protected val cacheEnabled = true

    /** Subclasses: tell me what key to use for updates. */
    protected def cacheKeyForUpdate(w: W): Any

    private object Entry {
        def apply(transid: TransactionId, state: State, value: Option[Future[W]]): Entry = {
            new Entry(transid, new AtomicReference(state), value)
        }
    }

    /**
     * The entries in the cache will be a triple of (transid, State, Future[W]?).
     *
     * We need the transid in order to detect whether we have won the race to add an entry to the cache.
     */
    private class Entry(
        @volatile private var transid: TransactionId,
        val state: AtomicReference[State],
        @volatile private var value: Option[Future[W]]) {

        def invalidate(): Unit = {
            state.set(InvalidateInProgress)
        }

        def unpack(): Future[W] = {
            value getOrElse Future.failed(StaleRead(state.get))
        }

        def writeDone()(implicit logger: Logging): Boolean = {
            logger.debug(this, "write finished")(transid)
            trySet(WriteInProgress, Cached)
        }

        def readDone()(implicit logger: Logging): Boolean = {
            logger.debug(this, "read finished")(transid)
            trySet(ReadInProgress, Cached)
        }

        def trySet(expectedState: State, desiredState: State): Boolean = {
            state.compareAndSet(expectedState, desiredState)
        }

        def grabWriteLock(newTransid: TransactionId, expectedState: State, newValue: Future[W]): Boolean = synchronized {
            val swapped = trySet(expectedState, WriteInProgress)
            if (swapped) {
                value = Option(newValue)
                transid = newTransid
            }
            swapped
        }

        def grabInvalidationLock() = state.set(InvalidateInProgress)
    }

    /**
     * This method posts a delete to the backing store, and either directly invalidates the cache entry
     * or informs any outstanding transaction that it must invalidate the cache on completion.
     */
    protected def cacheInvalidate[R](key: Any, invalidator: => Future[R])(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[R] = {
        if (cacheEnabled) {
            logger.info(this, s"invalidating $key")

            // try inserting our desired entry...
            val desiredEntry = Entry(transid, InvalidateInProgress, None)
            cache(key)(desiredEntry) flatMap { actualEntry =>
                // ... and see what we get back
                val currentState = actualEntry.state.get

                currentState match {
                    case Cached =>
                        // nobody owns the entry, forcefully grab ownership
                        // note: if a new cache lookup is received while
                        // the invalidator has not yet completed (and hence the actual entry
                        // removed from the cache), such lookup operations will still be able
                        // to return the value that is cached, and this is acceptable (under
                        // the eventual consistency model) as long as such lookups do not
                        // mutate the state of the cache to violate the invalidation that is
                        // about to occur (this is eventually consistent and NOT sequentially
                        // consistent since the cache lookup and the setting of the
                        // InvalidateInProgress bit are not atomic
                        invalidateEntryAfter(invalidator, key, actualEntry)

                    case ReadInProgress | WriteInProgress =>
                        if (actualEntry.trySet(currentState, InvalidateWhenDone)) {
                            // then the pre-existing owner will take care of the invalidation
                            invalidator
                        } else {
                            // the pre-existing reader or writer finished and so must
                            // explicitly invalidate here
                            invalidateEntryAfter(invalidator, key, actualEntry)
                        }

                    case InvalidateInProgress =>
                        if (actualEntry == desiredEntry) {
                            // we own the entry, so we are responsible for cleaning it up
                            invalidateEntryAfter(invalidator, key, actualEntry)
                        } else {
                            // someone else requested an invalidation already
                            invalidator
                        }

                    case InvalidateWhenDone =>
                        // a pre-existing owner will take care of the invalidation
                        invalidator
                }
            }
        } else invalidator // not caching
    }

    /**
     * This method may initiate a read from the backing store, and potentially stores the result in the cache.
     */
    protected def cacheLookup[Wsuper >: W](key: Any, generator: => Future[W], fromCache: Boolean = cacheEnabled)(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[W] = {
        if (fromCache) {
            val promise = Promise[W] // this promise completes with the generator value

            // try inserting our desired entry...
            val desiredEntry = Entry(transid, ReadInProgress, Some(promise.future))
            cache(key)(desiredEntry) flatMap { actualEntry =>
                // ... and see what we get back

                actualEntry.state.get match {
                    case Cached =>
                        logger.debug(this, "cached read")
                        makeNoteOfCacheHit(key)
                        actualEntry.unpack

                    case ReadInProgress =>
                        if (actualEntry == desiredEntry) {
                            logger.debug(this, "read initiated");
                            makeNoteOfCacheMiss(key)
                            // updating the cache with the new value is done in the listener
                            // and will complete unless an invalidation request or an intervening
                            // write occur in the meantime
                            listenForReadDone(key, actualEntry, generator, promise)
                            actualEntry.unpack
                        } else {
                            logger.debug(this, "coalesced read")
                            makeNoteOfCacheHit(key)
                            actualEntry.unpack
                        }

                    case WriteInProgress | InvalidateInProgress =>
                        logger.debug(this, "reading around an update in progress")
                        makeNoteOfCacheMiss(key)
                        generator
                }
            }
        } else generator // not caching
    }

    /**
     * This method posts an update to the backing store, and potentially stores the result in the cache.
     */
    protected def cacheUpdate(doc: W, key: Any, generator: => Future[Winfo])(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[Winfo] = {
        if (cacheEnabled) {
            // try inserting our desired entry...
            val desiredEntry = Entry(transid, WriteInProgress, Some(Future.successful(doc)))
            cache(key)(desiredEntry) flatMap { actualEntry =>
                // ... and see what we get back

                if (actualEntry == desiredEntry) {
                    // then this transaction won the race to insert a new entry in the cache
                    // and it is responsible for updating the cache entry...
                    logger.info(this, s"write initiated on new cache entry")
                    listenForWriteDone(key, actualEntry, generator)
                } else {
                    // ... otherwise, some existing entry is in the way, so try to grab a write lock
                    val currentState = actualEntry.state.get
                    val allowedToAssumeCompletion = currentState == Cached || currentState == ReadInProgress

                    if (allowedToAssumeCompletion && actualEntry.grabWriteLock(transid, currentState, desiredEntry.unpack)) {
                        // this transaction is now responsible for updating the cache entry
                        logger.info(this, s"write initiated on existing cache entry invalidating $key")
                        listenForWriteDone(key, actualEntry, generator)
                    } else {
                        // there is a conflicting operation in progress on this key
                        logger.info(this, s"write-around (i.e., not cached) under $currentState")
                        invalidateEntryAfter(generator, key, actualEntry)
                    }
                }
            }
        } else generator // not caching
    }

    def cacheSize: Int = cache.size

    /**
     * Log a cache hit
     *
     */
    private def makeNoteOfCacheHit(key: Any)(implicit transid: TransactionId, logger: Logging) = {
        transid.mark(this, LoggingMarkers.DATABASE_CACHE_HIT, s"[GET] serving from cache: $key")(logger)
    }

    /**
     * Log a cache miss
     *
     */
    private def makeNoteOfCacheMiss(key: Any)(implicit transid: TransactionId, logger: Logging) = {
        transid.mark(this, LoggingMarkers.DATABASE_CACHE_MISS, s"[GET] serving from datastore: $key")(logger)
    }

    /**
     * We have initiated a read (in cacheLookup), now handle its completion:
     * 1. either cache the result if there is no intervening delete or update, or
     * 2. invalidate the cache because there was an intervening delete or update.
     */
    private def listenForReadDone(key: Any, entry: Entry, generator: => Future[W], promise: Promise[W])(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Unit = {

        // helper that writes completion resulted in a failure
        def readOops(t: Throwable): Unit = {
            invalidateEntry(key, entry)
            promise.failure(t)
        }

        generator onComplete {
            case Success(value) =>
                // if the datastore read was successful, then try to transition to the Cached state
                logger.debug(this, "read backend part done, now marking cache entry as done")

                if (entry.readDone()) {
                    // cache entry is still in ReadInProgress and successful transitioned to Cached
                    // hence the new value is cached
                    promise success value
                } else {
                    entry.state.get match {
                        case InvalidateWhenDone =>
                            // some transaction requested an invalidation, so remove the key from the cache
                            invalidateEntry(key, entry)
                        case WriteInProgress =>
                            // do nothing, the write will handle the entry
                            ()
                        case _ =>
                            // this should not happen
                            // if this ever happens, this cache impl is buggy
                            val error = ConcurrentOperationUnderRead(entry.state.get)
                            logger.error(this, error.toString)
                            readOops(error)
                    }
                }

            case Failure(t) =>
                // oops, the datastore read failed. invalidate the cache entry
                // note: that this might be a perfectly legitimate failure,
                // e.g. a lookup for a non-existant key; we need to pass the particular t through
                readOops(t)
        }
    }

    /**
     * We have initiated a write, now handle its completion:
     * 1. either cache the result if there is no intervening delete or update, or
     * 2. invalidate the cache cache because there was an intervening delete or update
     */
    private def listenForWriteDone(key: Any, entry: Entry, generator: => Future[Winfo])(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[Winfo] = {

        generator andThen {
            case Success(_) =>
                // if the datastore write was successful, then transition to the Cached state
                logger.debug(this, "write backend part done, now marking cache entry as done")

                if (entry.writeDone()) {
                    // entry transitioned from WriteInProgress to Cached state
                    logger.info(this, s"write all done, caching $key ${entry.state.get}")
                } else {
                    // state transition from WriteInProgress to Cached fails so invalidate
                    // the entry in the cache
                    if (entry.state.get != InvalidateWhenDone) {
                        // if this ever happens, this cache impl is buggy
                        logger.error(this, ConcurrentOperationUnderUpdate.toString)
                    } else {
                        logger.info(this, s"write done, but invalidating cache entry as requested")
                    }
                    invalidateEntry(key, entry)
                }

            case Failure(_) => invalidateEntry(key, entry) // datastore write failed, invalidate cache entry
        }
    }

    /** Immediately invalidates the given entry. */
    private def invalidateEntry(key: Any, entry: Entry)(
        implicit transid: TransactionId, logger: Logging): Unit = {
        logger.info(this, s"invalidating $key")
        entry.invalidate()
        cache remove key
    }

    /** Invalidates the given entry after a given invalidator completes. */
    private def invalidateEntryAfter[R](invalidator: => Future[R], key: Any, entry: Entry)(
        implicit ec: ExecutionContext, transid: TransactionId, logger: Logging): Future[R] = {

        entry.grabInvalidationLock()
        invalidator andThen {
            case _ => invalidateEntry(key, entry)
        }
    }

    /** This is the backing store. */
    private val cache: Cache[Entry] = LruCache(timeToLive = 5.minutes)
}
