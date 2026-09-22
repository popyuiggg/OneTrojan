package hev.htproxy;

/** JNI contract provided by hev-socks5-tunnel. */
public final class TProxyService {
    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int tunFd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}
