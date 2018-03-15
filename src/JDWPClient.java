import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
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
    private Set<Long> refSet;
    private DecimalFormat df;
    private Map<Location, Long> bpLimit;

    public VirtualMachine vm;

    public int depthLim; // depth limit for visiting object fields
    public int arrayLim; // limit for number of elements of arrays to dump
    public Predicate<Field> fldFilter; // filter for dumping fields
    public Predicate<StackFrame> stackFrameFilter; // filter for dumping stack frames

    public JDWPClient(String host, int port, boolean append) throws IOException, IllegalConnectorArgumentsException {
        AttachingConnector connector = Bootstrap.virtualMachineManager().
                attachingConnectors().stream().filter(
                c -> c.name().equals("com.sun.jdi.SocketAttach")).
                findFirst().get();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        bpLimit = new HashMap<>();
        args.get("hostname").setValue(host);
        args.get("port").setValue(Integer.toString(port));

        resetRefs();
        df = new DecimalFormat();
        df.setMaximumFractionDigits(2);
        fldFilter = x -> true;
        stackFrameFilter = x -> true;
        depthLim = 1000;

        new File("facts").mkdir();
        cge = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicCallGraphEdge.facts", append)));
        reach = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicReachableMethod.facts", append)));
        vpt = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicVarPointsTo.facts", append)));
        ctx = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicContext.facts", append)));
        fldpt = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicInstanceFieldPointsTo.facts", append)));
        hobj = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicNormalHeapObject.facts", append)));
        halloc = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicNormalHeapAllocation.facts", append)));
        arrpt = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicArrayIndexPointsTo.facts", append)));
        staticpt = new PrintWriter(new BufferedWriter(new FileWriter("facts/DynamicStaticFieldPointsTo.facts", append)));

        vm = connector.attach(args);
        // todo figure this out
        appendToFile(ctx, immutable, "<>", "<>", "<>", 0, "<>", 0);
    }

    public void resetRefs() {
        refSet = new HashSet<>();
    }

    public void setBreakpoint(Location loc, long times) {
        if (loc == null) {
            return;
        }
        BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(loc);
        bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        bpLimit.put(loc, times);
        bp.setEnabled(true);
    }

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

    public static Optional<String> getMethodName(Method m) {
        try {
            return Optional.of("<" + m.declaringType().name() + ": " + m.returnType().name() + " " + m.name() + "(" + m.argumentTypeNames().stream().collect(Collectors.joining(",")) + ")>");
        } catch (ClassNotLoadedException e) {
            return Optional.empty();
        }
    }

    public static Optional<String> getVarName(StackFrame frame, String var) {
        return getMethodName(frame.location().method()).map(s -> s + "/" + var);
    }

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
            while (iter2.hasNext()) {
                Location from = iter2.next().location();
                Location to = iter1.next().location();
                getMethodName(from.method()).ifPresent(fromName ->
                    getMethodName(to.method()).ifPresent(toName ->
                        appendToFile(cge, fromName, from.lineNumber(), toName, immutable, immutable)
                    )
                );
            }

            frames.forEach(frame -> getMethodName(frame.location().method()).ifPresent(
                s -> appendToFile(reach, s)
            ));
            List<StackFrame> framesToDump = frames.stream().filter(stackFrameFilter).collect(Collectors.toList());
            for (int i = 0; i < framesToDump.size(); i++) {
                dumpLocals(framesToDump.get(i));
            }
        } catch (IncompatibleThreadStateException e) {
            e.printStackTrace();
        } catch (InternalException e) {
            System.out.println(e.getMessage());
        }
    }

    public void dumpLocals(StackFrame frame) {
        try {
            Map<LocalVariable, Value> varMap = frame.getValues(frame.visibleVariables());
            long t0 = System.currentTimeMillis();
            String thisRep = dumpValue(frame.thisObject(), refSet, depthLim);
            if (thisRep != null) {
                getVarName(frame, "@this").ifPresent(thisVarName -> appendToFile(vpt, immutable, thisRep, immutable, thisVarName));
            }
            ListIterator<Value> argIter = frame.getArgumentValues().listIterator();
            // todo test params
            t0 = System.currentTimeMillis();
            while (argIter.hasNext()) {
                int parIdx = argIter.nextIndex();
                String paramRep = dumpValue(argIter.next(), refSet, depthLim);
                if (paramRep != null) {
                    getVarName(frame, "@parameter" + parIdx).ifPresent(
                            paramName -> appendToFile(vpt, immutable, paramRep, immutable, paramName));
                }
            }
            varMap.forEach((var, val) -> {
                // System.out.println(varName + " -> " + val.toString());
                // todo do smth with contexts?
                String valRep = dumpValue(val, refSet, depthLim);
                getVarName(frame, var.name()).ifPresent(varName -> {
                    if (valRep != null) {
                        appendToFile(vpt, immutable, valRep, immutable, varName + "#");
                    }
                });
            });
        } catch (AbsentInformationException e) {}
    }

    private long hashObj(ObjectReference ref) {
        return ref.type().signature().hashCode()*100000 + ref.uniqueID();
    }

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
                    if (visited.add(hashObj(obj))) {
                        if (depthLim > 0) {
                            for (Map.Entry<Field, Value> f : obj.getValues(((ReferenceType) (obj.type())).allFields()).entrySet()) {
                                if (fldFilter.test(f.getKey())) {
                                    if (f.getKey().isStatic()) {
                                        if (visited.add((long)(obj.type().signature().hashCode()) * 1000000 + f.getKey().hashCode())) {
                                            Value staticVal = ((ReferenceType) obj.type()).getValue(f.getKey());
                                            String value = dumpValue(staticVal, visited, depthLim - 1);
                                            if (value != null) {
                                                appendToFile(staticpt, f.getKey().name(), obj.type().name(), value);
                                            }
                                        }
                                    } else {
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
                        appendToFile(halloc, 0, "?", obj.type().name(), curVal);
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

    public static void appendToFile(PrintWriter p, Object... fields) {
        p.println(Arrays.stream(fields).map(o -> o.toString().replace('\t', ' ')).collect(Collectors.joining("\t")));
    }

    public synchronized void dumpAllThreads(Predicate<ThreadReference> threadFilter) {
        List<ThreadReference> threads = vm.allThreads().stream().filter(threadFilter).collect(Collectors.toList());
        threads.forEach(ThreadReference::suspend);
        threads.forEach(this::dumpThread);
        threads.forEach(ThreadReference::resume);
        flush();
    }

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

    public void handleEvents() {
        EventQueue eventQueue = vm.eventQueue();
        while (true) {
            EventSet eventSet = null;
            try {
                eventSet = eventQueue.remove();
                synchronized (this) {
                    for (Event e : eventSet) {
                        if (e instanceof BreakpointEvent) {
                            Location loc = ((BreakpointEvent) e).location();
                            Long count = bpLimit.get(loc);
                            if (count != null) {
                                if (count > 0) {
                                    count--;
                                    bpLimit.put(loc, count);
                                } else {
                                    e.request().disable();
                                    continue;
                                }
                            }
                            System.out.println("Break on: " + loc + " (" + count + " remaining)");
                            dumpThread(((BreakpointEvent) e).thread());
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

    public synchronized void suspendAll() {
        vm.allThreads().forEach(ThreadReference::suspend);
    }

    public synchronized void resumeAll() {
        vm.allThreads().forEach(ThreadReference::resume);
    }
}
