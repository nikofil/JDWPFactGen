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
import com.sun.tools.jdi.ArrayReferenceImpl;
import com.sun.tools.jdi.ClassTypeImpl;
import com.sun.tools.jdi.ObjectReferenceImpl;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class JDWPClient {

    private static final String immutable = "<Immutable dctx>";
    private static PrintWriter cge;
    private static PrintWriter reach;
    private static PrintWriter vpt;
    private static PrintWriter ctx;
    private static PrintWriter fldpt;
    private static PrintWriter hobj;
    private static PrintWriter halloc;
    private static PrintWriter arrpt; // todo
    private static PrintWriter methinvo; // todo
    private static PrintWriter staticpt; // todo
    private static PrintWriter strheap; // todo
    private Set<Long> refSet;

    public VirtualMachine vm;

    public int depthLim; // depth limit for visiting object fields
    public Predicate<Field> fldFilter; // filter for dumping fields
    public Predicate<StackFrame> stackFrameFilter; // filter for dumping stack frames

    public JDWPClient(String host, int port, boolean append) throws IOException, IllegalConnectorArgumentsException {
        AttachingConnector connector = Bootstrap.virtualMachineManager().
                attachingConnectors().stream().filter(
                c -> c.name().equals("com.sun.jdi.SocketAttach")).
                findFirst().get();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(Integer.toString(port));

        resetRefs();
        fldFilter = x -> true;
        stackFrameFilter = x -> true;
        depthLim = 1000;

        cge = new PrintWriter(new BufferedWriter(new FileWriter("DynamicCallGraphEdge.facts", append)));
        reach = new PrintWriter(new BufferedWriter(new FileWriter("DynamicReachableMethod.facts", append)));
        vpt = new PrintWriter(new BufferedWriter(new FileWriter("DynamicVarPointsTo.facts", append)));
        ctx = new PrintWriter(new BufferedWriter(new FileWriter("DynamicContext.facts", append)));
        fldpt = new PrintWriter(new BufferedWriter(new FileWriter("DynamicInstanceFieldPointsTo.facts", append)));
        hobj = new PrintWriter(new BufferedWriter(new FileWriter("DynamicNormalHeapObject.facts", append)));
        halloc = new PrintWriter(new BufferedWriter(new FileWriter("DynamicNormalHeapAllocation.facts", append)));
        arrpt = new PrintWriter(new BufferedWriter(new FileWriter("DynamicArrayIndexPointsTo.facts", append)));
        methinvo = new PrintWriter(new BufferedWriter(new FileWriter("DynamicMethodInvocation.facts", append)));
        staticpt = new PrintWriter(new BufferedWriter(new FileWriter("DynamicStaticFieldPointsTo.facts", append)));
        strheap = new PrintWriter(new BufferedWriter(new FileWriter("DynamicStringHeapObject.facts", append)));

        vm = connector.attach(args);
        // todo figure this out
        appendToFile(ctx, immutable, "<>", "<>", "<>", 0, "<>", 0);
    }

    public void resetRefs() {
        refSet = new HashSet<>();
    }

    public void setBreakpoint(Location loc) {
        BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(loc);
        bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        bp.setEnabled(true);
    }

    public void breakOnMethod(String className, String methodName, boolean all) {
        for (ReferenceType c : vm.classesByName(className)) {
            try {
                c.methodsByName(methodName).stream().filter(method -> all || method.declaringType().name().equals(className)).forEach(m -> {
                    setBreakpoint(m.location());
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
        methinvo.flush();
        staticpt.flush();
        strheap.flush();
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
        methinvo.close();
        staticpt.close();
        strheap.close();
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

    public void dumpThread(ThreadReference thread) {
        thread.suspend();
        try {
            List<StackFrame> frames = thread.frames();

            Iterator<StackFrame> iter1 = frames.iterator();
            Iterator<StackFrame> iter2 = frames.iterator();
            iter2.next();
            while (iter2.hasNext()) {
                Location from = iter2.next().location();
                Location to = iter1.next().location();
                appendToFile(cge, getMethodName(from.method()).get(), from.lineNumber(), getMethodName(to.method()).get(), immutable, immutable);
            }

            frames.forEach(frame -> appendToFile(reach, getMethodName(frame.location().method()).get()));
            frames.stream().filter(stackFrameFilter).forEach(this::dumpLocals);
        } catch (IncompatibleThreadStateException e) {
            e.printStackTrace();
        }
        thread.resume();
    }

    public void dumpLocals(StackFrame frame) {
        try {
            Map<LocalVariable, Value> varMap = frame.getValues(frame.visibleVariables());
            String thisRep = dumpValue(frame.thisObject(), refSet, depthLim);
            String thisVarName = getVarName(frame, "@this").get();
            appendToFile(vpt, immutable, thisRep, immutable, thisVarName);
            ListIterator<Value> argIter = frame.getArgumentValues().listIterator();
            // todo test params
            while (argIter.hasNext()) {
                int parIdx = argIter.nextIndex();
                String paramRep = dumpValue(argIter.next(), refSet, depthLim);
                String paramName = getVarName(frame, "@parameter" + parIdx).get();
                appendToFile(vpt, immutable, paramRep, immutable, paramName);
            }
            varMap.forEach((var, val) -> {
                String varName = getVarName(frame, var.name()).get();
                // System.out.println(varName + " -> " + val.toString());
                // todo do smth with contexts?
                String valRep = dumpValue(val, refSet, depthLim);
                appendToFile(vpt, immutable, valRep, immutable, varName);
            });
        } catch (AbsentInformationException e) {}
    }

    public String dumpValue(Value val, Set<Long> visited, long depthLim) {
        String rv;
        try {
            if (val instanceof ObjectReferenceImpl) {
                if (val instanceof ArrayReferenceImpl) {
                    // todo
                    rv = "array!";
                } else {
                    ObjectReferenceImpl obj = (ObjectReferenceImpl) val;
                    String curVal = obj.type().name() + "@" + obj.uniqueID();
                    if (visited.add(obj.uniqueID())) {
                        if (depthLim > 0) {
                            for (Map.Entry<Field, Value> f : obj.getValues(((ClassTypeImpl) (obj.type())).fields()).entrySet()) {
                                if (fldFilter.test(f.getKey())) {
                                    String value = dumpValue(f.getValue(), visited, depthLim - 1);
                                    // System.out.println("field " + f.getKey().name() + " -> " + value);
                                    appendToFile(fldpt, curVal, f.getKey().name(), f.getKey().declaringType().name(), value);
                                }
                            }
                        }
                        // todo can improve this? (alloc line and method)
                        appendToFile(halloc, 0, "?", obj.type().name(), curVal);
                    }
                    // System.out.println("val: " + curVal);
                    rv = curVal;
                }
            } else if (val != null) {
                rv = val.toString();
            } else {
                rv = "null";
            }
            appendToFile(hobj, rv, immutable, rv);
        } catch (Exception e) {
            rv = "null";
        }
        return rv;
    }

    public static void appendToFile(PrintWriter p, Object... fields) {
        p.println(Arrays.stream(fields).map(o -> o.toString().replace('\t', ' ')).collect(Collectors.joining("\t")));
    }

    public void handleEvents() {
        EventQueue eventQueue = vm.eventQueue();
        while (true) {
            try {
                EventSet eventSet = eventQueue.remove();
                for (Event e : eventSet) {
                    if (e instanceof BreakpointEvent) {
                        ThreadReference thread = ((BreakpointEvent) e).thread();
                        System.out.println("Break on: " + thread.frame(0).location().method());
                        dumpThread(thread);
                        System.out.println("Thread dumped!");
                    }
                }
                flush();
                eventSet.resume();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
