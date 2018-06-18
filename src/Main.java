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
            client.breakOnMethod("org.alfresco.web.ui.repo.component.shelf.UIClipboardShelfItem", "encodeBegin", 1, true);

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
            //client.breakOnMethod("javax.servlet.ServletRequest", "getParameter", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getHeader", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getParameterValues", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getCookies", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getHeader", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getHeaders", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getHeaderNames", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getPathInfo", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getPathTranslated", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getQueryString", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getRequestedSessionId", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getRequestURI", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getRequestURL", 2, false);
            //client.breakOnMethod("javax.servlet.http.HttpServletRequest", "getServletPath", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getAttribute", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getAttributeNames", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getInputStream", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getParameter", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getParameterNames", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getParameterValues", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getParameterMap", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getServerName", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getReader", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getRemoteAddr", 2, false);
            //client.breakOnMethod("javax.servlet.ServletRequest", "getRemoteHost", 2, false);
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
