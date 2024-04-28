/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package olr.ga;

import edu.tufts.eaftan.hprofparser.parser.HprofParser;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ObjectGraphAnalyzer {

    /**
     * Maps stack trace IDs to lists of sets of object IDs.
     * Each set represents a generation/age.
     */
    public static final Map<Integer, Set<Integer>[]> lifetimes = new HashMap<Integer, Set<Integer>[]>();

    /**
     * Maps object ID to stack trace ID.
     */
    public static final Map<Integer, Integer> object2Stacktrace = new HashMap<>();

    /**
     * Tree of stack trace elements.
     */
    public static final STTree sttree = new STTree();

    /**
     * Maximum object age to consider.
     */
    public static int maxAge = 1;

    /**
     * Minimum number of objects to consider alloc site for upgrande in generation.
     */
    public static int MIN_OBJ_GEN = 16;

    protected static final boolean DEBUG = false;
    protected static final boolean DEBUG_WARN = false;

    public static void usage() {
        System.out.println("Usage java ObjectGraphAnalyzer <olr-ar output dir> <dumps1> ... <dumpN>");
    }

    public static int targetGeneration(Set<Integer>[] lifetimes) {
        int maxIdx = 0;
        int counter = 0;

        for (int i = 0; i < lifetimes.length; i++) {
            counter += lifetimes[i].size();
            if (lifetimes[i].size() > lifetimes[maxIdx].size()) {
                maxIdx = i;
            }
        }
        return counter < MIN_OBJ_GEN ? 0 : maxIdx;
    }

    public static void processHeapDumps(String[] dumps) throws Exception {
        Date start, finish;
        int curAge = 1;

        for (String dump : dumps) {
            System.out.println(String.format("Processing dump %s...", dump));
            start =new Date();

            try {

                HeapDumpHandler hdh = new HeapDumpHandler(
                        lifetimes, object2Stacktrace, curAge);
                HprofParser parser = new HprofParser(hdh);

                DataInputStream in =
                        new DataInputStream(
                                new BufferedInputStream(
                                        new FileInputStream(dump)));
                parser.parse(in);
                in.close();
            } catch (EOFException e) {
                System.err.println("ERR: failed to to process " + dump);
                e.printStackTrace();
            }

            finish =new Date();
            System.out.println(String.format("Processing dump %s...Done (%s sec)",
                    dump, (finish.getTime()-start.getTime())/1000));
            curAge++;
            System.gc();
        }
        // TODO - print stats?
    }

    /**
     * Returns a map between a stack trace id and a stack trace.
     * @param path
     * @return
     * @throws Exception
     */
    private static ConcurrentHashMap<Integer, StackTraceElement[]> loadStackTraces(String path) throws Exception {
        ConcurrentHashMap<Integer, StackTraceElement[]> traces;
        ObjectInputStream ois =
                new ObjectInputStream(
                    new FileInputStream(path));
        traces = (ConcurrentHashMap<Integer, StackTraceElement[]>) ois.readObject();
        ois .close();
        return traces;
    }


    private static boolean filterStackTraceElement(StackTraceElement ste) {
        if (ste.getClassName().equals("com.google.monitoring.runtime.instrumentation.AllocationInstrumenter") && ste.getMethodName().equals("instrument")) {
            return true;
        }
        return false;
    }

    private static ConcurrentHashMap<Integer, StackTraceElement[]>
        filterStackTraces(
                ConcurrentHashMap<Integer, StackTraceElement[]> traces) throws Exception {
        Set<Integer> toremove = new HashSet<>();
        for(Entry<Integer, StackTraceElement[]> entry : traces.entrySet()) {
            // Filter by the number of occurences.
            if ((!lifetimes.containsKey(entry.getKey())) || (lifetimes.get(entry.getKey())[0].size() < MIN_OBJ_GEN)) {
                toremove.add(entry.getKey());
                continue;
            }
            // Filer by the package + class + method of occurence.
            for(StackTraceElement ste : entry.getValue()) {
                if (filterStackTraceElement(ste)) {
                    toremove.add(entry.getKey());
                    break;
                }
            }
        }

        for(Integer i : toremove) {
            traces.remove(i);
        }

        return traces;
    }

    private static void buildSTTree(ConcurrentHashMap<Integer, StackTraceElement[]> traces) {
        // <StackTrace id, stacktrace element array>
        for (Entry<Integer, StackTraceElement[]> entry : traces.entrySet()) {
            STTree node = sttree;
            StackTraceElement[] arr = entry.getValue();
            for (int i = arr.length - 1; i >= 0; i--) {
                StackTraceElement ste = arr[i];
                try {
                    int steHash = ste.hashCode();
                    if (node.children().containsKey(steHash)) {
                        node = node.children().get(steHash);
                        node.addSTID(entry.getKey());
                    } else {
                        STTree newNode = new STTree(node, ste);
                        node.addChild(steHash, newNode);
                        node = newNode;
                        node.addSTID(entry.getKey());
                    }
                }
                catch (Exception e) {
                    System.err.println("WARN: stack trace failed to get hashCode:" + ste);
                }
            }
        }
    }

    /**
     * Returns a map between a stack trace ID and a set of object IDs.
     * @param path
     * @throws Exception
     */
    public static void loadAllocationRecords(String path) throws Exception {
        DataInputStream ois = new DataInputStream(new BufferedInputStream (new FileInputStream(path)));
        try {
            while (true) {
                int objID = ois.readInt();
                int stID = ois.readInt();
                if (DEBUG) {
                    System.out.println("Loaded objID=" + objID + " stID=" + stID);
                }
                object2Stacktrace.put(objID, stID);
                add2Lifetime(objID, stID);
            }
        } catch (EOFException e) { }
        finally {
            ois.close();
        }
    }

    private static void add2Lifetime(int objID, int stID) {
        if (!lifetimes.containsKey(stID)) {
            HashSet[] array = new HashSet[maxAge + 1];
            for (int i = 0; i < maxAge + 1; i++) {
                array[i] = new HashSet<Integer>();
            }
            lifetimes.put(stID, array);
        }
        lifetimes.get(stID)[0].add(objID);
    }

    public static void loadAllocationData(String outputDir) throws Exception {
        long allocationCounter = 0;
        long traceCounter = 0;
        ConcurrentHashMap<Integer, StackTraceElement[]> traces = null;
        Date start =new Date();
        System.out.println(String.format("Loading allocation data..."));

        // For each file representing a stacktrace
        for (final File fileEntry : new File(outputDir).listFiles()) {
            String fname = fileEntry.getName();
            // Ignore if it is a directory
            if (fileEntry.isDirectory()) {
                System.err.println("WARN: avoiding directory " + fname);
                continue;
            }

            if (!fname.startsWith("olr-ar-")) {
                System.err.println("WARN: avoiding file " + fname);
                continue;
            }

            System.out.println("Processing " + fileEntry.getAbsolutePath() + " ...");
            if (fname.equals("olr-ar-traces")) {
                traces = loadStackTraces(fileEntry.getAbsolutePath());
                traceCounter = traces.size();
                System.out.println(String.format("Loaded %d traces!", traceCounter));
            } else {
                loadAllocationRecords(fileEntry.getAbsolutePath());
                allocationCounter =  object2Stacktrace.size();
                System.out.println(String.format("Loaded %d allocations!", allocationCounter));
            }
            System.out.println("Processing " + fileEntry.getAbsolutePath() + " ...Done");
            System.out.flush();
        }

        traces = filterStackTraces(traces);

        System.out.println("Building STTree ...");
        buildSTTree(traces);
        System.out.println("Building STTree ...Done");
        Date finish =new Date();
        System.out.println(String.format("Loading allocation data...Done (%s sec)",
                (finish.getTime()-start.getTime())/1000));
    }
    /**
     * @param args the command line arguments
     * @throws java.lang.Exception
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }

        // We may not receive any dumps...
        maxAge = args.length - 1;

        // Load stacktraces
        loadAllocationData(args[0]);

        if (maxAge > 0) {
            String[] rest_args = new String[maxAge];
            System.arraycopy(args, 1, rest_args, 0, rest_args.length);
            processHeapDumps(rest_args);
        }

        // TODO - improve code!
        Writer output = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("/tmp/oga.output"), "UTF-8"));
        try {
            output.write(sttree.toOutput());
        } finally {
            output.close();
        }

        // TODO - improve code!
        Writer xml = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("/tmp/oga.xml"), "UTF-8"));
        try {
            xml.write(sttree.toXML());
        } finally {
            xml.close();
        }

    }
}
