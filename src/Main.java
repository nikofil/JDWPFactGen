import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            final JDWPClient client = new JDWPClient("localhost", 5005, false);
            client.depthLim = 20;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> client.close()));
            client.waitForClass("org.dacapo.xalan.XSLTBench", 100);
            client.suspendAll();
            System.out.println("Found xalan");
            client.vm.allClasses()
                .stream()
                .filter(cl ->
                    cl.name().toLowerCase().contains("xalan") || cl.name().toLowerCase().contains("dacapo"))
                .forEach(referenceType -> referenceType.allMethods()
                    .forEach(method -> {
                        if ((!method.isNative()) && method.declaringType().name().equals(referenceType.name()))
                           client.setBreakpoint(method.location(), 200);
                    })
                );
            client.breakOnMethod("org.alfresco.repo.web.scripts.comments.CommentsPost", "executeImpl", 200,false);
            client.resumeAll();
            client.handleEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (IllegalConnectorArgumentsException e) {
            e.printStackTrace();
        }
    }
}
