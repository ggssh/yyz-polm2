/*
 * Copyright (C) 2009 Google Inc.
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

package com.google.monitoring.runtime.instrumentation;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The logic for recording allocations, called from bytecode rewritten by
 * {@link AllocationInstrumenter}.
 *
 * @author jeremymanson@google.com (Jeremy Manson)
 * @author fischman@google.com (Ami Fischman)
 */
public class AllocationRecorder {
  static {
    // Sun's JVMs in 1.5.0_06 and 1.6.0{,_01} have a bug where calling
    // Instrumentation.getObjectSize() during JVM shutdown triggers a
    // JVM-crashing assert in JPLISAgent.c, so we make sure to not call it after
    // shutdown.  There can still be a race here, depending on the extent of the
    // JVM bug, but this seems to be good enough.
    // instrumentation is volatile to make sure the threads reading it (in
    // recordAllocation()) see the updated value; we could do more
    // synchronization but it's not clear that it'd be worth it, given the
    // ambiguity of the bug we're working around in the first place.
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        if (DEBUG) {
          LOG("running shutdown hook...");
        }

        setInstrumentation(null);
        flushAllocStreams();
        flushTraces();

        if (DEBUG) {
          LOG("running shutdown hook...Done");
        }
      }
    });
  }

  // Debug flags
  protected static final boolean DEBUG = false;
  protected static final boolean DEBUG_ALLOCS = false;
  protected static final boolean DEBUG_WARNS = false;

  // Where alloc statistics are going to be placed by default.
  protected static String OUTPUT_DIR = "/tmp";

  // See the comment above the addShutdownHook in the static block above
  // for why this is volatile.
  private static volatile Instrumentation instrumentation = null;

  static Instrumentation getInstrumentation() {
    return instrumentation;
  }

  static void setInstrumentation(Instrumentation inst) {
    instrumentation = inst;
  }

  // Used for reentrancy checks
  public static final ThreadLocal<Boolean> recordingAllocation =
          new ThreadLocal<Boolean>();

  // Thread-local stream for writting <obj ID, trace ID>.
  public static final ThreadLocal<DataOutputStream> allocStream =
          new ThreadLocal<DataOutputStream>();

  // List of thread-local streams that need to be flushed when the VM quits.
  // Note that this should not be a map indexed by thread ID because the thread
  // ID might be reused.
  public static final List<DataOutputStream> allocStreams =
          new LinkedList<DataOutputStream>();

  // Global map for traces. Multiple threads might allocate multiple objects
  // through the same code location, thus reusing the same trace.
  public static final ConcurrentHashMap<Integer, StackTraceElement[]> traces =
          new ConcurrentHashMap<Integer, StackTraceElement[]>();

  public static void addAllocStreams(DataOutputStream oos) {
    synchronized (allocStreams) {
      allocStreams.add(oos);
    }
  }

  public static void flushTraces() {
    String output = OUTPUT_DIR + "/olr-ar-traces";
    try {
      ObjectOutputStream oos =
            new ObjectOutputStream(
                  new FileOutputStream(output));
      oos.writeObject(traces);
      oos.flush();
      oos.close();
    }
    catch (Exception e) {
      if (DEBUG || DEBUG_WARNS) {
        LOG("ERR: unable to flush traces");
        e.printStackTrace();
      }
    }
  }

  public static void flushAllocStreams() {
    synchronized (allocStreams) {
      for (DataOutputStream oos : allocStreams) {
        try {
          oos.close();
          LOG("Closing " + oos + " (" + oos.size() + " bytes)");
        }
        catch (Exception e) {
          if (DEBUG || DEBUG_WARNS) {
            LOG("ERR: unable to flush alloc stream");
            e.printStackTrace();
          }
        }
      }
    }
  }

  // TODO - improve logging. If should be inside LOG or LOG_WARN
  public static synchronized void LOG(String msg) {
    System.err.println("[olr-ar] " + msg);
  }

  private static int hashStackTrace(StackTraceElement[] st) {
    int result = 37;
    for (StackTraceElement ste : st) {
      try {
        result = 37*result + ste.hashCode();
      }
      catch (Exception e) {
        if (DEBUG || DEBUG_WARNS) {
          LOG("WARN: failed to get hashCode for " + ste);
        }
        return 0;
      }
    }
    return result;
  }

  public static void recordAllocation(Class<?> cls, Object newObj) {
    // The use of replace makes calls to this method relatively ridiculously
    // expensive.
    String typename = cls.getName().replace('.', '/');
    recordAllocation(-1, typename, newObj);
  }

  /**
   * Records the allocation.  This method is invoked on every allocation
   * performed by the system.
   *
   * @param count the count of how many instances are being
   *   allocated, if an array is being allocated.  If an array is not being
   *   allocated, then this value will be -1.
   * @param desc the descriptor of the class/primitive type
   *   being allocated.
   * @param newObj the new <code>Object</code> whose allocation is being
   *   recorded.
   */
  public static void recordAllocation(int count, String desc, Object newObj) {
    if (recordingAllocation.get() == Boolean.TRUE) {
      return;
    } else {
      recordingAllocation.set(Boolean.TRUE);
    }

    // Copy value into local variable to prevent NPE that occurs when
    // instrumentation field is set to null by this class's shutdown hook
    // after another thread passed the null check but has yet to call
    // instrumentation.getObjectSize()
    Instrumentation instr = instrumentation;
    if (instr != null) {
      StackTraceElement[] st = Thread.currentThread().getStackTrace();
      int objID = System.identityHashCode(newObj);
      int stID = hashStackTrace(st);

      if (stID == 0) {
        LOG("WARN: avoided stack trace with stID = " + stID);
        recordingAllocation.set(Boolean.FALSE);
        return;
      }

      // Add trace if it does not exist already.
      traces.putIfAbsent(stID, st);

      // Create oos if it does not exist already.
      DataOutputStream oos = allocStream.get();
      try {
        if (oos == null) {
          String output = OUTPUT_DIR + "/olr-ar-" + Thread.currentThread().getId();
          oos = new DataOutputStream(
                  new BufferedOutputStream(
                    new FileOutputStream(output, true)));
          allocStream.set(oos);
          addAllocStreams(oos);
          LOG("Creating " + OUTPUT_DIR + "/olr-ar-" + Thread.currentThread().getId() + " (" + oos + ")" );
        }

        // Write objID and stID to stream (32 + 32 bits)
        synchronized (oos) {
            // TODO - check if this synchronized block is necessary.
            oos.writeInt(objID);
            oos.writeInt(stID);
        }
      } catch (Exception e) {
        if (DEBUG || DEBUG_WARNS) {
          LOG(String.format("ERR: unable to write to output stream for thread %d",
           Thread.currentThread().getId()));
          e.printStackTrace();
        }
      }

      if (DEBUG || DEBUG_ALLOCS) {
        LOG(String.format("oos=%s\tst=%d\tobj=%d\tcount=%d\tdesc=%s\tin=Thread-%d",
           oos,stID, objID, count, desc, Thread.currentThread().getId()));
      }
    }

    recordingAllocation.set(Boolean.FALSE);
  }

  /**
   * Helper method to force recording; for unit tests only.
   * @param count the number of objects being allocated.
   * @param desc the descriptor of the class of the object being allocated.
   * @param newObj the object being allocated.
   */
  public static void recordAllocationForceForTest(int count, String desc,
                                                  Object newObj) {
    // Make sure we get the right number of elided frames
    recordAllocationForceForTestReal(count, desc, newObj, 2);
  }

  /**
   * Helper method to force recording; for unit tests only.
   * @param count the number of objects being allocated.
   * @param desc the descriptor of the class of the object being allocated.
   * @param newObj the object being allocated.
   * @param recurse A recursion count.
   */
  public static void recordAllocationForceForTestReal(
      int count, String desc, Object newObj, int recurse) {
    if (recurse != 0) {
      recordAllocationForceForTestReal(count, desc, newObj, recurse - 1);
      return;
    }
  }
}
