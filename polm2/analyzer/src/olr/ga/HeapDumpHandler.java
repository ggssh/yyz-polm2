/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package olr.ga;

import edu.tufts.eaftan.hprofparser.handler.NullRecordHandler;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Value;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class HeapDumpHandler extends NullRecordHandler {

  // private final ConcurrentHashMap<Integer, Set<Integer>[]> lifetimes;
  // private final Map<Integer, Integer> object2Stacktrace;
  // private final int curAge;

  // public HeapDumpHandler(
  //   ConcurrentHashMap<Integer, Set<Integer>[]> lifetimes,
  //         Map<Integer, Integer> object2Stacktrace,
  //         int curAge) {
  //     this.lifetimes = lifetimes;
  //     this.object2Stacktrace = object2Stacktrace;
  //     this.curAge = curAge;
  // }

  // public HeapDumpHandler(
  //         Map<Integer, Integer> object2Stacktrace,
  //         int curAge) {
  //     this.object2Stacktrace = object2Stacktrace;
  //     this.curAge = curAge;
  // }

  @Override
  public void instanceDump(long objId, int stackTraceSerialNum,
      long classObjId, Value<?>[] instanceFieldValues) {
    if (objId > Integer.MAX_VALUE && ObjectGraphAnalyzer.DEBUG_WARN) {
        System.err.println("WARN " + objId + " does not fit in 4 bytes");
    }
    if (ObjectGraphAnalyzer.DEBUG) {
        System.out.print(String.format("Dumped object with id=%d \t class id=%d\n", objId, classObjId));
    }
    // update(objId);
    ObjectGraphAnalyzer.update(objId);
  }

  @Override
  public void objArrayDump(long objId, int stackTraceSerialNum,
      long elemClassObjId, long[] elems) {
    if (ObjectGraphAnalyzer.DEBUG) {
        System.out.print(String.format("Dumped object array with id=%d \t elem class id=%d\n", objId, elemClassObjId));
    }
    // update(objId);
    ObjectGraphAnalyzer.update(objId);
  }

  @Override
  public void primArrayDump(long objId, int stackTraceSerialNum,
      byte elemType, Value<?>[] elems) {
    if (ObjectGraphAnalyzer.DEBUG) {
        System.out.print(String.format("Dumped prim array with id=%d \t elem type id=%d\n", objId, elemType));
    }
    // update(objId);
    ObjectGraphAnalyzer.update(objId);
  }
}
