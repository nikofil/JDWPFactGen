import com.sun.jdi.ReferenceType;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            final JDWPClient client = new JDWPClient("localhost", 5005, false);
            client.depthLim = 100;
            client.stackFrameLim = 50;
            client.arrayLim = 30;
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
            //System.out.println("dumping...");
            //client.dumpAllThreads((x) -> true);
            //System.out.println("dumped!");
            //client.dumpAllEvery(600000, (x) -> true);
            //client.resumeAll();
            //try{
                //Thread.sleep(60000);
            //} catch(Exception e) {}
            client.suspendAll();
            System.out.println("setting bps for tracking allocations");
            //client.vm.allClasses().stream().filter(ReferenceType::isPrepared).
                //filter(cl -> cl.name().toLowerCase().startsWith("javax") || cl.name().startsWith("java.lang.String") || cl.name().startsWith("java.util.Hash") || cl.name().contains(".web.app.") || cl.name().contains(".repo.")).
                    //forEach(client::trackAllocation);
            System.out.println("done");
            client.vm.allClasses()
                .stream()
                .filter(ReferenceType::isPrepared)
                .filter(cl ->
                    cl.name().toLowerCase().contains("alfresco") && (/*cl.name().toLowerCase().contains(".tag.") || cl.name().toLowerCase().contains(".web.ui.") || cl.name().toLowerCase().contains(".web.app.servlet")*/ cl.name().toLowerCase().contains(".webdav.")) && !cl.name().toLowerCase().contains("mozilla"))
                .forEach(referenceType -> referenceType.allMethods()
                    .forEach(method -> {
                        if ((!method.isNative()) && method.name().contains("executeImpl") && (method.declaringType().equals(referenceType) || method.declaringType().name().contains("apache"))) {
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
