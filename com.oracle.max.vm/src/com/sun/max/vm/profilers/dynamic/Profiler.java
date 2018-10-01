/*
 * Copyright (c) 2017, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.sun.max.vm.profilers.dynamic;

import com.sun.max.annotate.C_FUNCTION;
import com.sun.max.annotate.INLINE;
import com.sun.max.annotate.NEVER_INLINE;
import com.sun.max.annotate.NO_SAFEPOINT_POLLS;
import com.sun.max.program.ProgramError;
import com.sun.max.vm.Log;
import com.sun.max.vm.MaxineVM;
import com.sun.max.vm.VMOptions;
import com.sun.max.vm.heap.Heap;
import com.sun.max.vm.runtime.FatalError;
import com.sun.max.vm.runtime.SafepointPoll;
import com.sun.max.vm.thread.VmThread;

import static com.sun.max.vm.MaxineVM.isHosted;

public class Profiler {

    @C_FUNCTION
    static native void dynamicProfiler_lock();

    @C_FUNCTION
    static native void dynamicProfiler_unlock();

    /**
     * Histogram: the data structure that stores the profiling outcome.
     *
     */
    //histogram initialization
    public static int initialSize = 50;
    public static int currentSize = initialSize;
    public static int growStep = 10;
    public static HistogramCell[] histogram; // = new HistogramCell[initialSize];
    public static int profilingCycle;

    private static boolean PrintHistogram;

    /**
     * Use -XX:+PrintHistogram flag to accompany the profiler stats with a complete histogram view.
     */
    static {
        VMOptions.addFieldOption("-XX:", "PrintHistogram", Profiler.class, "Print Dynamic Profiler's Histogram after every GC. (default: false)", MaxineVM.Phase.PRISTINE);
    }

    public Profiler() {
        histogram = new HistogramCell[initialSize];
        //initial cell allocation for our histogram
        for (int i = 0; i < initialSize; i++) {
            histogram[i] = new HistogramCell();
        }
        profilingCycle = 0;

    }

    /**
     * Grow the histogram array after GC
     * @param when
     */
    public void growHistogram(Heap.GCCallbackPhase when) {

        Log.println("post gc");
        Object tmp = new Object();

    }

    /**
     * Returns the found index if is existed, or -1 if is not.
     * @param size
     * @return found index
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    public int searchFor(int size) {
        for (int i = 0; i < lastEntry; i++) {
            if (histogram[i][0] == size) {
                return i;
            }
        }
        return -1;
    }

    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    public int searchForGC(int size) {
        for (int i = 0; i < lastEntryGC; i++) {
            if (histogramGC[i][0] == size) {
                return i;
            }
        }
        return -1;
    }

    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    public void recordNewEntry(int size, int index) {
        histogram[index][0] = size;
        histogram[index][1] = 1;
    }

    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    public void recordNewEntryGC(int size, int index) {
        histogramGC[index][0] = size;
        histogramGC[index][1] = 1;
    }

    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    public void increment(int index) {
        histogram[index][1]++;
    }

    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    public void incrementGC(int index) {
        histogramGC[index][1]++;
    }

    /**
     * Updates the histogram with the size of the profiled object.
     * If that size has never been met again, a new bin/bucket is inserted.
     * Else, the value of the corresponding bin/bucket is incremented.
     *
     * @param size
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public void record(int size) {
        int entry = searchFor(size);

        if (entry == -1) {
            recordNewEntry(size, lastEntry);
            lastEntry++;
        } else {
            increment(entry);
        }
        totalRecordedObjects++;
        totalObjectsize = totalObjectsize + size;
    }

    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public void recordGC(int size) {
        int entry = searchForGC(size);

        if (entry == -1) {
            recordNewEntryGC(size, lastEntryGC);
            lastEntryGC++;
        } else {
            incrementGC(entry);
        }
        totalRecordedObjectsGC++;
        totalObjectsizeGC = totalObjectsizeGC + size;
    }

    /**
     * This method is called when a profiled object is allocated.
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public void profile(int size) {
        //if (VmThread.current().PROFILE) {
            //if (MaxineVM.isRunning()) {
        final boolean lockDisabledSafepoints = lock();
        record(size);
        unlock(lockDisabledSafepoints);
            //}
        //}
    }

    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public void profileGC(int size) {
        //if (VmThread.current().PROFILE) {
        //if (MaxineVM.isRunning()) {
        final boolean lockDisabledSafepoints = lock();
        recordGC(size);
        unlock(lockDisabledSafepoints);
        //}
        //}
    }

    /**
     * A simple sort histogram implementation (using bubble sort).
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public void sortHistogram() {
        int n = lastEntry + 1;
        int k;
        for (int m = n; m >= 0; m--) {
            for (int i = 0; i < n - 1; i++) {
                k = i + 1;
                if (histogram[i][0] > histogram[k][0]) {
                    swapEntries(i, k);
                }
            }
        }
    }

    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public void swapEntries(int i, int j) {
        int temp0;
        int temp1;
        temp0 = histogram[i][0];
        temp1 = histogram[i][1];
        histogram[i][0] = histogram[j][0];
        histogram[i][1] = histogram[j][1];
        histogram[j][0] = temp0;
        histogram[j][1] = temp1;
    }

    /**
     * Sort and print Histogram.
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    public void printHistogram() {
        sortHistogram();

        Log.println("====HISTOGRAM====");
        for (int i = 1; i < lastEntry; i++) {
            //if (histogram[i][0] > 0) {
            Log.print("[");
            Log.print(histogram[i][0]);
            Log.print("]\t\t");
            Log.println(histogram[i][1]);
            //}
        }
        Log.print("Total histogram objects =");
        Log.println(totalRecordedObjects);
        Log.println("=======END=======");

    }

    /**
     * Round a number with decimals.
     * @param number
     * @param decimals
     * @return
     */
    @INLINE
    public double roundDecimals(double number, int decimals) {
        int factor = 1;

        //calc the factor out of the requested decimals
        for (int i = 0; i < decimals; i++) {
            factor = factor * 10;
        }

        //extract the whole part using casting
        int wholePart = (int) number;

        //calculate the decimal part
        double decimalPart = number - wholePart;
        decimalPart = decimalPart * factor;
        int intDecimalPart = (int) decimalPart;
        decimalPart = (double) intDecimalPart;
        decimalPart =  decimalPart / factor;

        //add whole and decimal parts and return
        return wholePart + decimalPart;
    }

    /**
     * Dump Profiler findings/stats to Maxine's Log output (for validation purposes).
     * TODO: create a -XX option for that functionality
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public void printStats() {
        final boolean lockDisabledSafepoints = lock();
        final float reportInMbs = (float) Heap.reportUsedSpace() / 1048576;
        final float histogramInMbs = (float) totalObjectsize / 1048576;

        Log.print("Reported heap used space = ");
        Log.print(reportInMbs);
        Log.println(" MB");

        Log.print("Histogram total object size = ");
        Log.print(histogramInMbs);
        Log.println(" MB");

        padding = reportInMbs - histogramInMbs;
        Log.print("TLAB Padding = ");
        Log.print(padding);
        Log.println(" MB\n");

        if (PrintHistogram) {
            printHistogram();
        }

        unlock(lockDisabledSafepoints);
    }

    /**
     * Reset the histogram and post GC operations.
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public void resetHistogram() {
        final boolean lockDisabledSafepoints = lock();
        Log.print("Collected heap used space = ");
        Log.println(Heap.reportUsedSpace());

        Log.print("HistogramGC heap used space = ");
        Log.println(totalObjectsizeGC);
        //Log.print("Collected heap used space = ");
        long survived = Heap.reportUsedSpace();
        //Log.print(survived/1048576);
        //Log.println(" MB");
        //Log.println(" ");
        for (int i = 0; i < lastEntry; i++) {
            histogram[i][0] = 0;
            histogram[i][1] = 0;

            histogramGC[i][0] = 0;
            histogramGC[i][1] = 0;
        }
        lastEntry = 0;
        totalObjectsize = (int) survived;
        lastEntryGC = 0;
        totalObjectsizeGC = 0;

        ///
        growHistogram(Heap.GCCallbackPhase.AFTER);

        unlock(lockDisabledSafepoints);
    }


    private static VmThread lockOwner;
    private static int lockDepth;

    /**
     * Gets the thread that current holds the log lock.
     */
    public static VmThread lockOwner() {
        return lockOwner;
    }

    /**
     * lock() and unlock() methods have been implemented according to the Log.lock() and Log.unlock() ones.
     *
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public static boolean lock() {
        if (isHosted()) {
            return true;
        }

        boolean wasDisabled = SafepointPoll.disable();
        Profiler.dynamicProfiler_lock();
        if (lockDepth == 0) {
            FatalError.check(lockOwner == null, "dynamic profiler lock should have no owner with depth 0");
            lockOwner = VmThread.current();
        }
        lockDepth++;
        return !wasDisabled;
    }

    /**
     * lock() and unlock() methods have been implemented according to the Log.lock() and Log.unlock() ones.
     *
     */
    @NO_SAFEPOINT_POLLS("dynamic profiler call chain must be atomic")
    @NEVER_INLINE
    public static void unlock(boolean lockDisabledSafepoints) {
        if (isHosted()) {
            return;
        }

        --lockDepth;
        FatalError.check(lockDepth >= 0, "mismatched lock/unlock");
        FatalError.check(lockOwner == VmThread.current(), "dynamic profiler lock should be owned by current thread");
        if (lockDepth == 0) {
            lockOwner = null;
        }
        Profiler.dynamicProfiler_unlock();
        ProgramError.check(SafepointPoll.isDisabled(), "Safepoints must not be re-enabled in code surrounded by Profiler.lock() and Profiler.unlock()");
        if (lockDisabledSafepoints) {
            SafepointPoll.enable();
        }
    }
}
