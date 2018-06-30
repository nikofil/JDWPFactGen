import com.sun.jdi.ReferenceType;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            final JDWPClient client = new JDWPClient("localhost", 5005, false);
            client.depthLim = 20;
            client.stackFrameLim = 30;
            client.arrayLim = 5;
            client.classLoadHandler = (ref) -> {
                String refStr = ref.toString();
                if (refStr.contains("alfresco") && refStr.contains("Test")) {
                    System.out.println("handler: " + refStr);
                    client.breakOnRefType(ref);
                }
            };
            Runtime.getRuntime().addShutdownHook(new Thread(() -> client.close()));
            client.suspendAll();
            //System.out.println("setting bps for tracking allocations");
            //client.vm.allClasses().stream().filter(ReferenceType::isPrepared).
                //filter(cl -> cl.name().toLowerCase().startsWith("javax") || cl.name().startsWith("java.lang.String") || cl.name().startsWith("java.util.Hash") || cl.name().contains(".web.app.") || cl.name().contains(".repo.")).
                    //forEach(client::trackAllocation);
            //System.out.println("done");

            client.vm.allClasses()
                .stream()
                .filter(ReferenceType::isPrepared)
                .filter(cl ->
                    cl.name().toLowerCase().contains("servlet") && (cl.name().toLowerCase().contains("download"))/* && !cl.name().toLowerCase().contains("mozilla")*/)
                .forEach(referenceType -> referenceType.allMethods()
                    .forEach(method -> {
                        if ((!method.isNative()) && (method.name().equals("generateBrowserURL") || method.name().equals("processDownloadRequest"))/* && (method.declaringType().equals(referenceType))*/) {
                           client.setBreakpoint(method.location(), 1);
                           System.out.println("break on " + method.location());
                        }
                    })
                );
            System.out.println("done bping");
            client.resumeAll();
            client.handleEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (IllegalConnectorArgumentsException e) {
            e.printStackTrace();
        }
    }
}
