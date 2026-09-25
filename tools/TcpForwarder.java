import java.io.*;
import java.net.*;

public class TcpForwarder {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java TcpForwarder <listenIp> <listenPort> <targetHost> <targetPort>");
            System.exit(1);
        }
        String listenIp = args[0];
        int listenPort = Integer.parseInt(args[1]);
        String targetHost = args[2];
        int targetPort = Integer.parseInt(args[3]);

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(listenIp), listenPort));
        System.out.println("READY:" + server.getLocalPort());
        System.out.flush();

        while (!server.isClosed()) {
            Socket client = server.accept();
            System.out.println("ACCEPTED:" + client.getRemoteSocketAddress());
            System.out.flush();

            new Thread(() -> {
                try (Socket target = new Socket(targetHost, targetPort)) {
                    target.setTcpNoDelay(true);
                    client.setTcpNoDelay(true);
                    Thread t1 = new Thread(() -> pipe(client, target));
                    Thread t2 = new Thread(() -> pipe(target, client));
                    t1.start();
                    t2.start();
                    t1.join();
                    t2.join();
                } catch (Exception e) {
                    System.err.println("Forward error: " + e.getMessage());
                } finally {
                    try { client.close(); } catch (Exception ignored) {}
                }
            }).start();
        }
    }

    private static void pipe(Socket src, Socket dst) {
        byte[] buf = new byte[65536];
        int n;
        try {
            InputStream in = src.getInputStream();
            OutputStream out = dst.getOutputStream();
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (!dst.isOutputShutdown()) {
                    dst.shutdownOutput();
                }
            } catch (Exception ignored) {}
        }
    }
}
