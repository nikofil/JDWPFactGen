import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

/*
TODO
Local VPT (with the name as it appears in the Var relations)
Parameter VPT
this VPT
context
*/

public class JDWPClient {
    public static final String immutable = "<Immutable dctx>";
    public static PrintWriter cge;
    public static PrintWriter reach;
    public static PrintWriter vpt;
    public static PrintWriter ctx;

    public VirtualMachine vm;

    public JDWPClient(String host, int port, boolean append) {
        AttachingConnector connector = Bootstrap.virtualMachineManager().
                attachingConnectors().stream().filter(
                c -> c.name().equals("com.sun.jdi.SocketAttach")).
                findFirst().get();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(Integer.toString(port));
        try {
            cge = new PrintWriter(new BufferedWriter(new FileWriter("DynamicCallGraphEdge.facts", append)));
            reach = new PrintWriter(new BufferedWriter(new FileWriter("DynamicReachable.facts", append)));
            vpt = new PrintWriter(new BufferedWriter(new FileWriter("DynamicVarPointsTo.facts", append)));
            ctx = new PrintWriter(new BufferedWriter(new FileWriter("DynamicContext.facts", append)));

            vm = connector.attach(args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        appendToFile(ctx, immutable, 0, 0);
    }

    public void breakOnMethod(String className, String methodName) {
        for (ReferenceType c : vm.allClasses()) {
            if (c.name().equals(className)) {
                for (Method m : c.allMethods()) {
                    if (m.name().equals(methodName)) {
                        vm.eventRequestManager().createBreakpointRequest(m.location()).setEnabled(true);
                    }
                }
            }
        }
    }

    public void close() {
        System.out.println("Shutting down...");
        cge.close();
        reach.close();
        vpt.close();
        ctx.close();
    }

    public static Optional<String> getMethodName(Method m) {
        try {
            return Optional.of("<" + m.declaringType().name() + ": " + m.returnType().name() + " " + m.name() + "(" + m.argumentTypeNames().stream().collect(Collectors.joining(",")) + ")>");
        } catch (ClassNotLoadedException e) {
            return Optional.empty();
        }
    }

    public static Optional<String> getVarName(StackFrame frame, LocalVariable var) {
        return getMethodName(frame.location().method()).map(s -> s + "/" + var.name());
    }

    public static void dumpThread(ThreadReference thread) {
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
            frames.forEach(JDWPClient::dumpLocals);
        } catch (IncompatibleThreadStateException e) {
            e.printStackTrace();
        }
        thread.resume();
    }

    public static void dumpLocals(StackFrame frame) {
        try {
            Map<LocalVariable, Value> varMap = frame.getValues(frame.visibleVariables());
            varMap.forEach((var, val) -> {
                String varn = getVarName(frame, var).get();
                System.out.println(varn + " -> " + val.toString());
            });
        } catch (AbsentInformationException e) {}
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
                    }
                }
                eventSet.resume();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
