package com.acs;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.InetAddress;

public class SimpleServer {

    public static void main(String[] args) throws Exception {

        // Get local host IP
        InetAddress localAddress = InetAddress.getLocalHost();

        // Create JmDNS instance
        JmDNS jmdns = JmDNS.create(localAddress);

        // Service details
        String serviceType = "_acs._tcp.local.";
        String serviceName = "SimpleServer";
        int port = 6000;

        ServiceInfo serviceInfo = ServiceInfo.create(
                serviceType,
                serviceName,
                port,
                "Hello , this is Anti Cheat System mDNS Server"
        );

        // Register service
        jmdns.registerService(serviceInfo);

        System.out.println("[Server] SimpleServer registered via mDNS");
        System.out.println("[Server] IP   : " + localAddress.getHostAddress());
        System.out.println("[Server] Port : " + port);

        // Keep server alive
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("\n[Server] Shutting down...");
                jmdns.unregisterAllServices();
                jmdns.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        Thread.sleep(Long.MAX_VALUE);
    }
}
