import com.sun.jdi.ReferenceType;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;

public class MainTest {
    public static void main(String[] args) {
        try {
            final JDWPClient client = new JDWPClient("localhost", 5006, "facts2", false);
            client.depthLim = 10;
            client.stackFrameLim = 1;
            client.arrayLim = 3;
            //client.setClassLoadHandler((ref) -> {
                //String refStr = ref.toString();
                //if (refStr.contains("alfresco") && refStr.contains("Test")) {
                    //System.out.println("handler: " + refStr);
                    //client.breakOnRefType(ref);
                //}
            //});
            client.breakOnRegex("org.alfresco.*", "*Test");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> client.close()));
            client.handleEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (IllegalConnectorArgumentsException e) {
            e.printStackTrace();
        }
    }
}
