package com.acs;


import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.net.InetAddress;

public class SimpleClient {

    public static void main(String[] args) throws Exception {

        InetAddress localAddress = InetAddress.getLocalHost();
        JmDNS jmdns = JmDNS.create(localAddress);

        String serviceType = "_acs._tcp.local.";

        System.out.println("[Client] Searching for SimpleServer...");

        jmdns.addServiceListener(serviceType, new ServiceListener() {

            @Override
            public void serviceAdded(ServiceEvent event) {
                // Request full service info
                jmdns.requestServiceInfo(
                        event.getType(),
                        event.getName(),
                        true
                );
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
                System.out.println("[Client] Service removed: " + event.getName());
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                ServiceInfo info = event.getInfo();

                System.out.println("[Client] Service found!");
                System.out.println("  Name : " + info.getName());
                System.out.println("  IP   : " + info.getHostAddresses()[0]);
                System.out.println("  Port : " + info.getPort());
                System.out.println("  Desc : " + info.getNiceTextString());
            }
        });

        // Discovery window
        Thread.sleep(10000);

        jmdns.close();
        System.out.println("[Client] Discovery finished");
    }
}
