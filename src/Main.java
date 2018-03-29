import com.sun.jdi.ReferenceType;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            final JDWPClient client = new JDWPClient("localhost", 5005, false);
            client.depthLim = 10;
            client.arrayLim = 3;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> client.close()));
            //client.waitForClass("org.apache.xalan.templates.ElemChoose", 300);
            //client.suspendAll();
            //System.out.println("Found xalan");
            //client.vm.allClasses()
                //.stream()
                //.filter(cl ->
                    //cl.name().toLowerCase().contains("xalan") || cl.name().toLowerCase().contains("dacapo") || cl.name().toLowerCase().contains("apache") || cl.name().toLowerCase().contains("w3c"))
                //.forEach(referenceType -> referenceType.allMethods()
                    //.forEach(method -> {
                        //if ((!method.isNative()) && (method.declaringType().name().contains("xalan") || method.declaringType().name().contains("dacapo")|| method.declaringType().name().contains("apache")|| method.declaringType().name().contains("w3c")))
                           //client.setBreakpoint(method.location(), 1);
                    //})
                //);

            // client.breakOnMethod("org.alfresco.repo.web.scripts.comments.CommentsPost", "executeImpl", 10, false);
            System.out.println("dumping...");
            client.dumpAllThreads((x) -> true);
            System.out.println("dumped!");
            client.dumpAllEvery(600000, (x) -> true);
            client.resumeAll();
            try{
                Thread.sleep(60000);
            } catch(Exception e) {}
            client.suspendAll();
            client.vm.allClasses().forEach(client::trackAllocation);
            client.vm.allClasses()
                .stream()
                .filter(ReferenceType::isPrepared)
                .filter(cl ->
                    cl.name().toLowerCase().contains("alfresco") && cl.name().toLowerCase().contains(".web.") && !cl.name().toLowerCase().contains("mozilla"))
                .forEach(referenceType -> referenceType.allMethods()
                    .forEach(method -> {
                        if ((!method.isNative()) && (method.declaringType().name().contains("alfresco") || method.declaringType().name().contains("apache"))) {
                           client.setBreakpoint(method.location(), 1);
                           if (method.location() != null)
                           System.out.println("hey bp " + method.location().toString());
                        }
                    })
                );
            client.resumeAll();
            client.handleEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (IllegalConnectorArgumentsException e) {
            e.printStackTrace();
        }
    }
}
