import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.sun.jdi.request.EventRequest;

import java.io.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JDWPClient {

    private static final String immutable = "<Immutable dctx>";

    private static PrintWriter cge;
    private static PrintWriter reach;
    private static PrintWriter vpt;
    private static PrintWriter ctx;
    private static PrintWriter fldpt;
    private static PrintWriter hobj;
    private static PrintWriter halloc;
    private static PrintWriter arrpt;
    private static PrintWriter staticpt;
    private Set<Long> refSet; // set of visited values
    private Set<String> allocTracking; // types whose allocation is being tracked
    private DecimalFormat df;
    private Map<Location, Long> bpLimit;
    private Map<Long, Location> allocLocation; // where a value was allocated

    public VirtualMachine vm;

    public int depthLim; // depth limit for visiting object fields
    public int stackFrameLim; // max number of stack frames to dump
    public int arrayLim; // limit for number of elements of arrays to dump
    public Predicate<Field> fldFilter; // filter for dumping fields
    public Predicate<StackFrame> stackFrameFilter; // filter for dumping stack frames
    public ClassLoadEventHandler classLoadHandler; // callback for handling class load events

    /**
     * Creates a new JDWP client.
     *
     * @param host the URL of the host to connect
     * @param port the port to connect on
     * @param factdir the dir to output facts
     * @param append whether to append to the facts files or to add new ones
     */
    public JDWPClient(String host, int port, String factdir, boolean append) throws IOException, IllegalConnectorArgumentsException {
        AttachingConnector connector = Bootstrap.virtualMachineManager().
                attachingConnectors().stream().filter(
                c -> c.name().equals("com.sun.jdi.SocketAttach")).
                findFirst().get();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        bpLimit = new HashMap<>();
        allocLocation = new HashMap<>();
        allocTracking = new HashSet<>();
        args.get("hostname").setValue(host);
        args.get("port").setValue(Integer.toString(port));

        resetRefs();
        df = new DecimalFormat();
        df.setMaximumFractionDigits(2);
        fldFilter = x -> true;
        stackFrameFilter = x -> true;
        classLoadHandler = ref -> {};
        depthLim = 1000;
        stackFrameLim = 100;

        new File(factdir).mkdir();
        cge = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicCallGraphEdge.facts", append)));
        reach = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicReachableMethod.facts", append)));
        vpt = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicVarPointsTo.facts", append)));
        ctx = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicContext.facts", append)));
        fldpt = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicInstanceFieldPointsTo.facts", append)));
        hobj = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicNormalHeapObject.facts", append)));
        halloc = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicNormalHeapAllocation.facts", append)));
        arrpt = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicArrayIndexPointsTo.facts", append)));
        staticpt = new PrintWriter(new BufferedWriter(new FileWriter(factdir+"/DynamicStaticFieldPointsTo.facts", append)));

        vm = connector.attach(args);
        // todo figure this out
        appendToFile(ctx, immutable, "<>", "<>", "<>", 0, "<>", 0);
    }

    /**
     * Set handler to call on class load events
     *
     * @param handler The handler object to call
     */
    public void setClassLoadHandler(ClassLoadEventHandler handler) {
        classLoadHandler = handler;
        // set BP on class load
        breakOnMethod("java.lang.ClassLoader", "postDefineClass", 9999999, true);
    }

    /**
     * Reset the set of visited objects (that won't be dumped again)
     */
    public void resetRefs() {
        refSet = new HashSet<>();
    }

    /**
     * Set breakpoint on location
     *
     * @param loc The location to break on
     * @param times How many times to break (0 for unlimited)
     */
    public void setBreakpoint(Location loc, long times) {
        if (loc == null) {
            return;
        }
        BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(loc);
        bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        if (times > 0) {
            bpLimit.put(loc, times);
        }
        bp.setEnabled(true);
    }

    /**
     * Set breakpoint on method
     *
     * @param className The name of the class containing the method
     * @param methodName The name of the method
     * @param times How many times to break (0 for unlimited)
     * @param all Whether to break on all methods or just the ones declared in this class
     */
    public void breakOnMethod(String className, String methodName, long times, boolean all) {
        for (ReferenceType c : vm.classesByName(className)) {
            try {
                c.methodsByName(methodName).stream().filter(method -> all || method.declaringType().name().equals(className)).forEach(m -> {
                    if (times != -1) {
                        setBreakpoint(m.location(), times);
                    }
                    System.out.println("Set breakpoint on " + className + ":" + methodName);
                });
            } catch (Exception e) {
                System.out.println("Failed to set breakpoint on " + className + ":" + methodName + " because of: " + e.getMessage());
            }
        }
    }

    /**
     * Break on classes exit events
     *
     * @param ref The type that contains the methods that will break on exit
     */
    public void breakOnRefType(ReferenceType ref) {
        MethodExitRequest me = vm.eventRequestManager().createMethodExitRequest();
        me.addClassFilter(ref);
        me.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        me.setEnabled(true);
    }

    /**
     * Break on classes matching a regex
     *
     * @param regs The strings that will be checked against the class name (see MethodExitRequest docs for more)
     */
    public void breakOnRegex(String... regs) {
        MethodExitRequest me = vm.eventRequestManager().createMethodExitRequest();
        for (String reg : regs)
            me.addClassFilter(reg);
        me.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        me.setEnabled(true);
    }

    /**
     * Flush output files
     */
    public void flush() {
        cge.flush();
        reach.flush();
        vpt.flush();
        ctx.flush();
        fldpt.flush();
        hobj.flush();
        halloc.flush();
        arrpt.flush();
        staticpt.flush();
    }

    /**
     * Close output files
     */
    public void close() {
        cge.close();
        reach.close();
        vpt.close();
        ctx.close();
        fldpt.close();
        hobj.close();
        halloc.close();
        arrpt.close();
        staticpt.close();
        System.out.println("Cleanup finished!");
    }

    /**
     * Create representation of a method
     *
     * @param m The method to be represented
     * @return An optional string that represents the method
     */
    public static Optional<String> getMethodName(Method m) {
        try {
            return Optional.of("<" + m.declaringType().name() + ": " + m.returnType().name() + " " + m.name() + "(" + m.argumentTypeNames().stream().collect(Collectors.joining(",")) + ")>");
        } catch (ClassNotLoadedException e) {
            return Optional.empty();
        }
    }

    /**
     * Create representation of a var
     *
     * @param frame The frame containing the var
     * @param var The var name
     * @return An optional string that represents the var
     */
    public static Optional<String> getVarName(StackFrame frame, String var) {
        return getMethodName(frame.location().method()).map(s -> s + "/" + var);
    }

    /**
     * Dump a thread's info to the facts files.
     * Visits the frames to the specified depth, and all the objects reachable from their vars to the specified depth.
     *
     * @param thread The thread to dump
     */
    public synchronized void dumpThread(ThreadReference thread) {
        try {
            List<StackFrame> frames = thread.frames();

            Iterator<StackFrame> iter1 = frames.iterator();
            Iterator<StackFrame> iter2 = frames.iterator();
            if (!iter2.hasNext()) {
                return;
            }
            iter2.next();
            long t0 = System.currentTimeMillis();
            int ifr = 0;
            while (iter2.hasNext()) {
                Location from = iter2.next().location();
                Location to = iter1.next().location();
                getMethodName(from.method()).ifPresent(fromName ->
                    getMethodName(to.method()).ifPresent(toName ->
                        // add a CGE
                        appendToFile(cge, fromName, from.lineNumber(), toName, immutable, immutable)
                    )
                );
                if (++ifr == stackFrameLim) {
                    break;
                }
            }

            frames.forEach(frame -> getMethodName(frame.location().method()).ifPresent(
                s -> appendToFile(reach, s)
            ));
            List<StackFrame> framesToDump = frames.stream().filter(stackFrameFilter).collect(Collectors.toList());
            for (int i = 0; i < framesToDump.size(); i++) {
                if (i == stackFrameLim) {
                    break;
                }
                // dump all frames up to a depth
                dumpLocals(framesToDump.get(i));
            }
        } catch (IncompatibleThreadStateException e) {
            e.printStackTrace();
        } catch (InternalException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Dump the locals of a stack frame, and the object reachable up to a specified depth.
     *
     * @param frame The frame to dump
     */
    public void dumpLocals(StackFrame frame) {
        try {
            Map<LocalVariable, Value> varMap = frame.getValues(frame.visibleVariables());
            long t0 = System.currentTimeMillis();
            String thisRep = dumpValue(frame.thisObject(), refSet, depthLim);
            if (thisRep != null) {
                // dump this
                getVarName(frame, "@this").ifPresent(thisVarName -> appendToFile(vpt, immutable, thisRep, immutable, thisVarName));
            }
            ListIterator<Value> argIter = frame.getArgumentValues().listIterator();
            t0 = System.currentTimeMillis();
            while (argIter.hasNext()) {
                // dump params
                int parIdx = argIter.nextIndex();
                String paramRep = dumpValue(argIter.next(), refSet, depthLim);
                if (paramRep != null) {
                    getVarName(frame, "@parameter" + parIdx).ifPresent(
                            paramName -> appendToFile(vpt, immutable, paramRep, immutable, paramName));
                }
            }
            varMap.forEach((var, val) -> {
                // todo do smth with contexts?
                // dump vars
                String valRep = dumpValue(val, refSet, depthLim);
                getVarName(frame, var.name()).ifPresent(varName -> {
                    if (valRep != null) {
                        appendToFile(vpt, immutable, valRep, immutable, varName + "#");
                    }
                });
            });
        } catch (AbsentInformationException e) {}
    }

    /**
     * Hash func for an object ref
     *
     * @param ref The object ref
     * @return The hash value
     */
    private long hashObj(ObjectReference ref) {
        return ref.type().signature().hashCode()*100000 + ref.uniqueID();
    }

    /**
     * Dumps a value representation and follows other values reached from it, up to a depth.
     *
     * @param val The value
     * @param visited A set of visited values, not to be visited again
     * @param depthLim How many times to follow inside each value's fields/elements etc
     * @return The representation of this value
     */
    public String dumpValue(Value val, Set<Long> visited, long depthLim) {
        String rv;
        try {
            if (val instanceof ObjectReference) {
                if (val instanceof ArrayReference) {
                    ArrayReference arr = (ArrayReference) val;
                    String curVal = arr.type().name() + "@" + arr.uniqueID();
                    if (visited.add(hashObj(arr)) && !(((ArrayType)arr.type()).componentType() instanceof PrimitiveType)) {
                        int acount = 0;
                        for (Value av : arr.getValues()) {
                            // dump array elements up to a limit
                            String avRef = dumpValue(av, visited, depthLim - 1);
                            if (avRef != null) {
                                appendToFile(arrpt, curVal, avRef);
                                if (++acount == arrayLim) {
                                    break;
                                }
                            }
                        }
                    }
                    rv = curVal;
                } else {
                    ObjectReference obj = (ObjectReference) val;
                    String curVal = obj.type().name() + "@" + obj.uniqueID();
                    // create an object representation
                    long hashVal = hashObj(obj);
                    if (visited.add(hashVal)) {
                        if (depthLim > 0) {
                            for (Map.Entry<Field, Value> f : obj.getValues(((ReferenceType) (obj.type())).allFields()).entrySet()) {
                                // dump filtered fields
                                if (fldFilter.test(f.getKey())) {
                                    if (f.getKey().isStatic()) {
                                        // dump a static field
                                        if (visited.add((long)(obj.type().signature().hashCode()) * 1000000 + f.getKey().hashCode())) {
                                            Value staticVal = ((ReferenceType) obj.type()).getValue(f.getKey());
                                            String value = dumpValue(staticVal, visited, depthLim - 1);
                                            if (value != null) {
                                                appendToFile(staticpt, f.getKey().name(), obj.type().name(), value);
                                            }
                                        }
                                    } else {
                                        // dump a field
                                        String value = dumpValue(f.getValue(), visited, depthLim - 1);
                                        // System.out.println("field " + f.getKey().name() + " -> " + value);
                                        if (value != null) {
                                            appendToFile(fldpt, curVal, f.getKey().name(), f.getKey().declaringType().name(), value);
                                        }
                                    }
                                }
                            }
                        }
                        // todo can improve this? (alloc line and method)
                        Location alloc = allocLocation.get(hashVal);
                        if (alloc != null) {
                            appendToFile(halloc, alloc.lineNumber(), getMethodName(alloc.method()).orElse("Unknown"), obj.type().name(), curVal);
                        } else {
                            appendToFile(halloc, 0, "Unknown", obj.type().name(), curVal);
                        }
                    }
                    // System.out.println("val: " + curVal);
                    rv = curVal;
                }
            } else if (val != null && !(val instanceof PrimitiveValue)) {
                rv = val.toString();
            } else {
                rv = null;
            }
            appendToFile(hobj, rv, immutable, rv);
        } catch (Exception e) {
            rv = null;
        }
        return rv;
    }

    /**
     * Append the given objects to a file, separated by tabs
     *
     * @param p The writer to write to
     * @param fields The objects to write
     */
    public static void appendToFile(PrintWriter p, Object... fields) {
        p.println(Arrays.stream(fields).map(o -> o.toString().replace('\t', ' ')).collect(Collectors.joining("\t")));
    }

    /**
     * Suspend, dump and resume all
     *
     * @param threadFilter A filter for which threads to dump
     */
    public synchronized void dumpAllThreads(Predicate<ThreadReference> threadFilter) {
        List<ThreadReference> threads = vm.allThreads().stream().filter(threadFilter).collect(Collectors.toList());
        threads.forEach(ThreadReference::suspend);
        threads.forEach(this::dumpThread);
        threads.forEach(ThreadReference::resume);
        flush();
    }

    /**
     * Suspend, dump and resume all periodically
     *
     * @param ms How many ms to wait each time
     * @param threadFilter A filter for which threads to dump
     */
    public void dumpAllEvery(long ms, Predicate<ThreadReference> threadFilter) {
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    System.out.println("Dumping all threads...");
                    dumpAllThreads(threadFilter);
                    System.out.println("All threads dumped");
                }
            } catch (Exception e) {
                System.out.println("Worker exception:");
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Handle breakpoint events, more details inside
     */
    public void handleEvents() {
        EventQueue eventQueue = vm.eventQueue();
        while (true) {
            EventSet eventSet = null;
            try {
                eventSet = eventQueue.remove();
                synchronized (this) {
                    for (Event e : eventSet) {
                        System.out.println("Got event: " + e);
                        if (e instanceof LocatableEvent) {
                            Location loc = ((LocatableEvent) e).location();
                            ThreadReference thread = ((LocatableEvent) e).thread();
                            if (loc.declaringType().name().equals("java.lang.ClassLoader")) {
                                // handle class loads
                                Value val = thread.frame(0).getArgumentValues().get(0);
                                ReferenceType refType = ((ClassObjectReference)val).reflectedType();
                                // call user handler if it's set
                                classLoadHandler.onClassLoad(refType);
                                continue;
                            } else if (allocTracking.contains(loc.toString())) {
                                try {
                                    if (thread.frameCount() > 2) {
                                        // track allocation location when <init> is called
                                        StackFrame f0 = thread.frame(0);
                                        StackFrame f1 = thread.frame(1);
                                        ObjectReference thisObj = f0.thisObject();
                                        if (thisObj != null && !thisObj.equals(f1.thisObject())) {
                                            allocLocation.put(hashObj(f0.thisObject()), f1.location());
                                        }
                                    }
                                } catch (IncompatibleThreadStateException e1) {
                                    e1.printStackTrace();
                                }
                            }
                            Long count = bpLimit.get(loc);
                            if (count != null) {
                                // check if BP limit was reached
                                if (count > 0) {
                                    // decrement remaining BPs for this location
                                    count--;
                                    bpLimit.put(loc, count);
                                    System.out.println("Break on: " + loc + " (" + count + " remaining)");
                                    dumpThread(thread);
                                } else {
                                    e.request().disable();
                                }
                            } else {
                                // no BP limit
                                System.out.println("Break on: " + loc);
                                dumpThread(thread);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (e instanceof VMDisconnectedException) {
                    return;
                }
                e.printStackTrace();
            } finally {
                flush();
                if (eventSet != null) {
                    eventSet.resume();
                }
            }
        }
    }

    /**
     * Wait until a class is loaded
     *
     * @param clsName The class to wait for
     * @param timestep How often to check for the class, in ms
     */
    public synchronized void waitForClass(String clsName, long timestep) {
        vm.allThreads().forEach(ThreadReference::suspend);
        while (vm.classesByName(clsName).size() == 0) {
            vm.allThreads().forEach(ThreadReference::resume);
            try {
                Thread.sleep(timestep);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            vm.allThreads().forEach(ThreadReference::suspend);
        }
        vm.allThreads().forEach(ThreadReference::resume);
    }

    /**
     * Suspend all threads
     */
    public synchronized void suspendAll() {
        vm.allThreads().forEach(ThreadReference::suspend);
    }

    /**
     * Resume all threads
     */
    public synchronized void resumeAll() {
        vm.allThreads().forEach(ThreadReference::resume);
    }

    /**
     * Track allocations in order to know where a given object was allocated (through the allocLocation map)
     *
     * @param ref The type to track the allocations of
     */
    public void trackAllocation(ReferenceType ref) {
        ref.methodsByName("<init>").forEach(method -> {
            if (method.declaringType().equals(ref) && !method.isNative()) {
                if (allocTracking.add(method.location().toString()))
                    setBreakpoint(method.location(), 0);
            }
        });
    }

    public interface ClassLoadEventHandler {
        public void onClassLoad(ReferenceType ref);
    }
}
