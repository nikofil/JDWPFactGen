import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            JDWPClient client = new JDWPClient("localhost", 5005, false);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> client.close()));
            client.vm.allThreads().stream()
                    .filter(thread -> thread.name().contains("alfresco"))
                    .forEach(client::dumpThread);
            System.out.println("Thread dump complete, listening for events");
            // client.handleEvents();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (IllegalConnectorArgumentsException e) {
            e.printStackTrace();
        }
    }
}
