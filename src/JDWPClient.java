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

public class JDWPClient {
    public static final String immutable = "<Immutable dctx>";
    public static PrintWriter cge;
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
            cge = new PrintWriter(new BufferedWriter(new FileWriter("DynamicCallGraphEdge.csv", append)));
            vpt = new PrintWriter(new BufferedWriter(new FileWriter("DynamicVarPointsTo.csv", append)));
            ctx = new PrintWriter(new BufferedWriter(new FileWriter("DynamicContext.csv", append)));
            vm = connector.attach(args);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        appendToFile(ctx, immutable, 0, 0);
    }

    public void close() {
        System.out.println("Shutting down...");
        cge.close();
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

    public static void dumpThread(ThreadReference thread) {
        thread.suspend();
        System.out.println(thread.toString());
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
            frames.forEach(stackFrame -> {
                System.out.println(" - " + JDWPClient.getMethodName(stackFrame.location().method()).get());
            });
        } catch (IncompatibleThreadStateException e) {
            e.printStackTrace();
        }
        thread.resume();
    }

    public static void appendToFile(PrintWriter p, Object... fields) {
        p.println(Arrays.stream(fields).map(Object::toString).collect(Collectors.joining("\t")));
    }

    public void handleEvents() {
        EventQueue eventQueue = vm.eventQueue();
        while (true) {
            try {
                EventSet eventSet = eventQueue.remove();
                for (Event e : eventSet) {
                    if (e instanceof BreakpointEvent) {
                        dumpThread(((BreakpointEvent) e).thread());
                    }
                }
                eventSet.resume();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
