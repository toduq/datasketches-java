/*
 * Copyright 2018, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.theta;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.yahoo.sketches.ResizeFactor;

/**
 * A shared sketch that is based on HeapQuickSelectSketch.
 * It reflects all data processed by a single or multiple update threads, and can serve queries at
 * any time
 *
 * @author eshcar
 */
class ConcurrentHeapQuickSelectSketch extends HeapQuickSelectSketch
    implements ConcurrentSharedThetaSketch {

  /**
   * Propagation thread
   */
  private volatile ExecutorService executorService_;

  /**
   * A flag to coordinate between several propagation threads
   */
  private final AtomicBoolean sharedPropagationInProgress_;

  /**
   * Theta value of concurrent sketch
   */
  private volatile long volatileThetaLong_;

  /**
   * A snapshot of the estimated number of unique entries
   */
  private volatile double volatileEstimate_;

  /**
   * Num of retained entries in which the sketch toggles from sync (exact) mode to async propagation mode
   */
  private final long exactLimit_;

  private final double maxConcurrencyError_;

  /**
   * An epoch defines an interval between two resets. A propagation invoked at epoch i cannot
   * affect the sketch at epoch j>i.
   */
  private volatile long epoch_;

  /**
   * Construct a new sketch instance on the java heap.
   *
   * @param lgNomLongs <a href="{@docRoot}/resources/dictionary.html#lgNomLogs">See lgNomLongs</a>.
   * @param seed       <a href="{@docRoot}/resources/dictionary.html#seed">See seed</a>
   * @param maxConcurrencyError the max concurrency error value
   */
  ConcurrentHeapQuickSelectSketch(final int lgNomLongs, final long seed,
      final double maxConcurrencyError) {
    super(lgNomLongs, seed, 1.0F, //p
        ResizeFactor.X1, //rf,
        false); //unionGadget
    volatileThetaLong_ = Long.MAX_VALUE;
    volatileEstimate_ = 0;
    maxConcurrencyError_ = maxConcurrencyError;
    exactLimit_ = getExactLimit();
    sharedPropagationInProgress_ = new AtomicBoolean(false);
    epoch_ = 0;
    initBgPropagationService();
  }

  //Sketch overrides

  @Override
  public double getEstimate() {
    return getEstimationSnapshot();
  }

  //HeapQuickSelectSketch overrides

  @Override
  public UpdateSketch rebuild() {
    super.rebuild();
    updateEstimationSnapshot();
    return this;
  }

  /**
   * Resets this sketch back to a virgin empty state.
   * Takes care of mutual exclusion with propagation thread.
   */
  @Override
  public void reset() {
    advanceEpoch();
    super.reset();
    volatileThetaLong_ = Long.MAX_VALUE;
    volatileEstimate_ = 0;
  }

  //ConcurrentSharedThetaSketch declarations

  @Override
  public void endPropagation(final AtomicBoolean localPropagationInProgress, final boolean isEager) {
    //update volatile theta, uniques estimate and propagation flag
    updateVolatileTheta();
    updateEstimationSnapshot();
    if (isEager) {
      sharedPropagationInProgress_.set(false);
    }
    if (localPropagationInProgress != null) {
      localPropagationInProgress.set(false); //clear local propagation flag
    }
  }

  @Override
  public double getEstimationSnapshot() {
    return volatileEstimate_;
  }

  @Override
  public long getVolatileTheta() {
    return volatileThetaLong_;
  }

  @Override
  public void awaitBgPropagationTermination() {
    try {
      executorService_.shutdown();
      while (!executorService_.awaitTermination(1, TimeUnit.MILLISECONDS)) {
        Thread.sleep(1);
      }
    } catch (final InterruptedException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void initBgPropagationService() {
    executorService_ = ConcurrentPropagationService.getExecutorService(Thread.currentThread().getId());
  }

  @Override
  public boolean isSharedEstimationMode() {
    return (getRetainedEntries(false) > exactLimit_) || isEstimationMode();
  }

  @Override
  public boolean propagate(final AtomicBoolean localPropagationInProgress,
                           final Sketch sketchIn, final long singleHash) {
    final long epoch = epoch_;
    if ((singleHash != NOT_SINGLE_HASH)                 //namely, is a single hash and
        && (getRetainedEntries(false) < exactLimit_)) { //a small sketch then propagate myself (blocking)
      if (!startEagerPropagation()) {
        endPropagation(localPropagationInProgress, true);
        return false;
      }
      if (!validateEpoch(epoch)) {
        endPropagation(null, true); // do not change local flag
        return true;
      }
      sharedHashUpdate(singleHash);
      endPropagation(localPropagationInProgress, true);
      return true;
    }
    // otherwise, be nonblocking, let background thread do the work
    final ConcurrentBackgroundThetaPropagation job =
        new ConcurrentBackgroundThetaPropagation(this, localPropagationInProgress, sketchIn, singleHash,
            epoch);
    executorService_.execute(job);
    return true;
  }

  @Override
  public long getK() {
    return 1L << getLgNomLongs();
  }

  @Override
  public double getError() {
    return maxConcurrencyError_;
  }

  @Override
  public void resetShared() {
    reset();
  }

  @Override
  public boolean startEagerPropagation() {
    while (!sharedPropagationInProgress_.compareAndSet(false, true)) {
    }
    return (!isSharedEstimationMode());// no eager propagation is allowed in estimation mode
  }

  @Override
  public void updateEstimationSnapshot() {
    volatileEstimate_ = super.getEstimate();
  }

  @Override
  public void sharedHashUpdate(final long hash) {
    hashUpdate(hash);
  }

  @Override
  public void updateVolatileTheta() {
    volatileThetaLong_ = getThetaLong();
  }

  @Override
  public boolean validateEpoch(final long epoch) {
    return epoch_ == epoch;
  }

  //restricted

  /**
   * Advances the epoch while there is no background propagation
   * This ensures a propagation invoked before the reset cannot affect the sketch after the reset
   * is completed.
   */
  private void advanceEpoch() {
    awaitBgPropagationTermination();
    startEagerPropagation();
    ConcurrentPropagationService.resetExecutorService(Thread.currentThread().getId());
    //noinspection NonAtomicOperationOnVolatileField
    // this increment of a volatile field is done within the scope of the propagation
    // synchronization and hence is done by a single thread
    epoch_++;
    endPropagation(null, true);
    initBgPropagationService();
  }
}
