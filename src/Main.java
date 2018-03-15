import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            final JDWPClient client = new JDWPClient("localhost", 5007, false);
            client.depthLim = 10;
            client.arrayLim = 3;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> client.close()));
            client.waitForClass("org.apache.xalan.templates.ElemChoose", 300);
            client.suspendAll();
            System.out.println("Found xalan");
            client.vm.allClasses()
                .stream()
                .filter(cl ->
                    cl.name().toLowerCase().contains("xalan") || cl.name().toLowerCase().contains("dacapo") || cl.name().toLowerCase().contains("apache") || cl.name().toLowerCase().contains("w3c"))
                .forEach(referenceType -> referenceType.allMethods()
                    .forEach(method -> {
                        if ((!method.isNative()) && (method.declaringType().name().contains("xalan") || method.declaringType().name().contains("dacapo")|| method.declaringType().name().contains("apache")|| method.declaringType().name().contains("w3c")))
                           client.setBreakpoint(method.location(), 1);
                    })
                );
            client.breakOnMethod("org.alfresco.repo.web.scripts.comments.CommentsPost", "executeImpl", 10, false);
            client.dumpAllEvery(800, (x) -> true);
            client.resumeAll();
            client.handleEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (IllegalConnectorArgumentsException e) {
            e.printStackTrace();
        }
    }
}
