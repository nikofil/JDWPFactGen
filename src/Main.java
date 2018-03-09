public class Main {
    public static void main(String[] args) {
        JDWPClient client = new JDWPClient("localhost", 5005, false);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> client.close()));
        client.vm.allThreads().stream()
                .filter(thread -> thread.name().contains("alfresco"))
                .forEach(JDWPClient::dumpThread);
        client.handleEvents();
    }
}
