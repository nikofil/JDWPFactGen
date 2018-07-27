import com.sun.jdi.ReferenceType;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;

public class MainAlfrescoGetMethod {
    public static void main(String[] args) {
        try {
            final JDWPClient client = new JDWPClient("localhost", 9092, "facts", false);
            client.depthLim = 20;
            client.stackFrameLim = 30;
            client.arrayLim = 5;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> client.close()));
            client.suspendAll();
            client.vm.allClasses()
                .stream()
                .filter(ReferenceType::isPrepared)
                .filter(cl ->
                    cl.name().toLowerCase().contains("alfresco") && cl.name().contains("repo.webdav.GetMethod"))
                .forEach(referenceType -> referenceType.allMethods()
                    .stream()
                    .filter(method -> (!method.isNative()) && method.name().equals("executeImpl") && method.declaringType().equals(referenceType))
                    .forEach(method -> {
                        client.setBreakpoint(method.location(), 1);
                        System.out.println("setting breakpoint on: " + method.location());
                    })
                );
            System.out.println("done setting breakpoints");
            client.resumeAll();
            client.handleEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (IllegalConnectorArgumentsException e) {
            e.printStackTrace();
        }
    }
}

