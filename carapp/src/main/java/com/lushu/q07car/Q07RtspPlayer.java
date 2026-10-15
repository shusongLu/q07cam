package com.lushu.q07car;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 自研 RTSP + H.265 → Surface 播放器（替代厂商 CaMediaPlayer）。
 *
 * 为什么自己写：
 *  - 车机侧摄像头是 H.265 的 RTSP 流，系统 MediaPlayer 的 RTSPSource 不认（会 Ignoring track）
 *  - 厂商 libffmpeg.so 只能单实例，且 close() 是否真发 TEARDOWN 不可控 ——
 *    实测"预连一侧后另一侧永远抢不到流"
 *
 * 链路：RTSP(OPTIONS/DESCRIBE/SETUP/PLAY) → UDP 收 RTP → 按 RFC 7798 拆 H.265
 *      → MediaCodec(video/hevc) 解到 Surface
 *
 * 传输用 UDP（服务端不支持 TCP interleaved）。
 */
public class Q07RtspPlayer {

    private static final String TAG = "Q07Rtsp";
    private static final Charset ASCII = Charset.forName("US-ASCII");

    // ---------------- RTP / H.265 ----------------
    private static final int NAL_AP = 48;   // 聚合包
    private static final int NAL_FU = 49;   // 分片包
    private static final int NAL_PACI = 50;

    private final String host;
    private final int port;
    private final String path;

    private Socket ctrl;
    private InputStream cin;
    private OutputStream cout;
    private DatagramSocket rtpSock, rtcpSock;

    private volatile boolean running;
    private volatile boolean playing;
    private Thread rtpThread;

    private MediaCodec codec;
    private Surface surface;
    private int width = 1920, height = 1080;
    /**
     * 要不要把解码结果画到 Surface 上。
     *
     * 隐藏的那一路设 false —— 走 releaseOutputBuffer(idx, false)（标准"丢帧"），
     * 于是：解码继续、缓冲队列不会堆、而且**完全不做纹理合成/上传**。
     * 实测 TextureView 每条流每帧的固定合成开销约 3.5% 单核，与窗口大小无关，
     * 所以"不渲染"才是省资源的关键；显示时切成 true，下一帧（~33ms）就出画。
     */
    private volatile boolean renderOutput = true;

    public void setRender(boolean r) { renderOutput = r; }

    private byte[] vps, sps, pps;
    private long frameCount;
    private long lastRecvAt;

    private String sessionId;
    private int cseq = 1;
    private int rtpPayloadType = 127;
    /** 从 SDP/响应里解析：内容基地址 与 轨道控制名（服务端会变，不能写死）。 */
    private String contentBase;
    private String trackControl;

    public Q07RtspPlayer(Context ctx, String url) {
        this.appCtx = ctx;
        // rtsp://host:port/path
        String s = url.startsWith("rtsp://") ? url.substring(7) : url;
        int slash = s.indexOf('/');
        String hostPort = slash >= 0 ? s.substring(0, slash) : s;
        this.path = slash >= 0 ? s.substring(slash + 1) : "";
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } else {
            host = hostPort;
            port = 554;
        }
    }

    private final Context appCtx;

    /**
     * 车机有 vlan6=172.16.6.x 这种内部车网，普通 App 默认不走它（Android 的 per-UID 路由），
     * 直接 connect 会失败。这里找出承载目标地址的那条网络，把 socket 绑上去。
     */
    private Network findNetworkFor(String hostPrefix) {
        try {
            ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) { Log.w(TAG, "没有 ConnectivityManager"); return null; }
            Network[] all = cm.getAllNetworks();
            Log.i(TAG, "可见网络数 = " + (all == null ? 0 : all.length));
            if (all == null) return null;
            for (Network n : all) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null || lp.getLinkAddresses() == null) continue;
                StringBuilder sb = new StringBuilder();
                for (LinkAddress la : lp.getLinkAddresses()) {
                    String a = la.getAddress() != null ? la.getAddress().getHostAddress() : null;
                    if (a == null) continue;
                    sb.append(a).append(' ');
                    if (a.startsWith(hostPrefix)) {
                        Log.i(TAG, "目标网段命中: " + a + " -> " + n);
                        return n;
                    }
                }
                Log.i(TAG, "网络 " + n + " 地址: " + sb);
            }
        } catch (Throwable t) {
            Log.w(TAG, "findNetworkFor: " + t);
        }
        return null;
    }

    private static String hostPrefix(String h) {
        int i = h.indexOf('.', h.indexOf('.') + 1);   // 取前三段，如 172.16.6.
        int j = h.indexOf('.', i + 1);
        return j > 0 ? h.substring(0, j + 1) : h;
    }

    private void bindToNetwork(Network net, java.net.Socket s) {
        if (net == null || s == null) return;
        try {
            net.bindSocket(s);
            Log.i(TAG, "已把 socket 绑到 " + net);
        } catch (Throwable t) {
            Log.w(TAG, "bindSocket 失败: " + t);
        }
    }

    private void bindToNetwork(Network net, DatagramSocket s) {
        if (net == null || s == null) return;
        try {
            net.bindSocket(s);
            Log.i(TAG, "已把 UDP socket 绑到 " + net);
        } catch (Throwable t) {
            Log.w(TAG, "bindSocket(udp) 失败: " + t);
        }
    }

    public long frames() { return frameCount; }
    public long lastRecv() { return lastRecvAt; }
    public boolean isPlaying() { return playing; }

    // ---------------------------------------------------------------- 生命周期

    public synchronized void start(Surface surface) {
        try {
            this.surface = surface;
            running = true;
            frameCount = 0;
            vps = sps = pps = null;

            ctrl = new Socket();
            Network net = findNetworkFor(hostPrefix(host));
            bindToNetwork(net, ctrl);
            ctrl.connect(new InetSocketAddress(host, port), 5000);
            ctrl.setSoTimeout(5000);
            cin = ctrl.getInputStream();
            cout = ctrl.getOutputStream();

            String base = "rtsp://" + host + ":" + port + "/" + path;

            rtsp("OPTIONS", base, null);

            String sdp = rtsp("DESCRIBE", base, "Accept: application/sdp");
            parseSdp(sdp);

            rtpSock = new DatagramSocket(0);
            bindToNetwork(net, rtpSock);
            int rtpPort = rtpSock.getLocalPort();
            rtcpSock = new DatagramSocket(rtpPort + 1);
            bindToNetwork(net, rtcpSock);
            rtpSock.setSoTimeout(1000);

            String setupResp = rtsp("SETUP", trackUrl(base),
                    "Transport: RTP/AVP;unicast;client_port=" + rtpPort + "-" + (rtpPort + 1));
            sessionId = headerValue(setupResp, "Session");
            if (sessionId == null) {
                Log.e(TAG, "SETUP 没拿到 Session：" + setupResp.replace("\r\n", " | "));
            }
            rtsp("PLAY", base, "Session: " + sessionId);

            rtpThread = new Thread(new Runnable() {
                @Override public void run() { rtpLoop(); }
            }, "q07-rtp");
            rtpThread.start();

            startKeepAlive();

            playing = true;
            Log.i(TAG, "start " + base + "  rtpPort=" + rtpPort + "  session=" + sessionId);
        } catch (Throwable t) {
            Log.e(TAG, "start 失败: " + t, t);
            stop();
        }
    }

    /**
     * 重新 PLAY（不重连）。服务端只有一把 RTP 发送口，谁后 PLAY 谁独占 ——
     * 切回来时只要 re-PLAY 就能把发送口抢回来，比重新连接快得多。
     */
    public synchronized boolean resume() {
        try {
            if (ctrl == null || sessionId == null) return false;
            playing = false;
            String base = "rtsp://" + host + ":" + port + "/" + path;
            String r = rtsp("PLAY", base, "Session: " + sessionId);
            playing = r.contains("200 OK");
            auLen = 0;
            fuLen = 0;
            Log.i(TAG, "resume -> " + (playing ? "OK" : r.replace("\r\n", " | ")));
            return playing;
        } catch (Throwable t) {
            Log.w(TAG, "resume 失败: " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 会话保活

    /**
     * LIVE555 的 RTSP 会话有"客户端存活检测"（默认约 65 秒）：我们既不发 RTCP RR、
     * 也不发任何 RTSP 请求的话，服务端会回收会话 —— 之后 re-PLAY 会拿到
     * "454 Session Not Found"，只能整条重连，肉眼就是"打灯先卡一下"。
     *
     * 所以每 20 秒发一次带 Session 的 OPTIONS 续命。与 start/stop/resume 共用
     * 同一把锁（它们都是 synchronized），避免读响应时互相抢包。
     */
    private Thread keepAliveThread;
    /** 成功保活几次（只打前几条日志，避免刷屏）。 */
    private int kaOk = 0;

    private void startKeepAlive() {
        stopKeepAlive();
        keepAliveThread = new Thread(new Runnable() {
            @Override public void run() {
                while (running && playing) {
                    try {
                        Thread.sleep(20000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!running || !playing) { return; }
                    synchronized (Q07RtspPlayer.this) {
                        if (ctrl == null || sessionId == null) { continue; }
                        try {
                            String url = "rtsp://" + host + ":" + port + "/" + path;
                            // GET_PARAMETER 是 RTSP 标准的会话保活，LIVE555 明确认它；
                            // 有些服务端只认 OPTIONS，所以失败后退回 OPTIONS。
                            String r = rtsp("GET_PARAMETER", url, "Session: " + sessionId);
                            if (!r.startsWith("RTSP/1.0 200")) {
                                r = rtsp("OPTIONS", url, "Session: " + sessionId);
                            }
                            if (!r.startsWith("RTSP/1.0 200")) {
                                Log.w(TAG, "keepalive 异常: " + r.replace("\r\n", " | "));
                            } else if (kaOk < 3) {
                                kaOk++;
                                Log.i(TAG, "keepalive OK (#" + kaOk + ")");
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "keepalive 失败: " + t);
                        }
                    }
                }
            }
        }, "q07-ka");
        keepAliveThread.start();
    }

    private void stopKeepAlive() {
        Thread t = keepAliveThread;
        keepAliveThread = null;
        if (t != null) { t.interrupt(); }
    }

    public synchronized void stop() {
        running = false;
        playing = false;
        stopKeepAlive();
        // TEARDOWN 只发不等响应 —— 等响应会因服务端不回而卡满 5 秒超时，
        // 把调用队列堵死（实测换边要 5.7 秒才返回）。
        try {
            if (sessionId != null && cout != null) {
                String base = "rtsp://" + host + ":" + port + "/" + path;
                String req = "TEARDOWN " + base + " RTSP/1.0\r\nCSeq: " + (cseq++) + "\r\n"
                        + "Session: " + sessionId + "\r\n\r\n";
                cout.write(req.getBytes(ASCII));
                cout.flush();
            }
        } catch (Throwable ignored) {
        }
        try { if (rtpSock != null) rtpSock.close(); } catch (Throwable ignored) {}
        try { if (rtcpSock != null) rtcpSock.close(); } catch (Throwable ignored) {}
        try { if (ctrl != null) ctrl.close(); } catch (Throwable ignored) {}
        rtpSock = rtcpSock = null;
        ctrl = null;
        cin = null;
        cout = null;
        releaseCodec();
        sessionId = null;
    }

    // ---------------------------------------------------------------- RTSP 控制

    private String rtsp(String method, String url, String extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq++).append("\r\n");
        sb.append("User-Agent: q07\r\n");
        if (extra != null) sb.append(extra).append("\r\n");
        sb.append("\r\n");
        cout.write(sb.toString().getBytes(ASCII));
        cout.flush();
        String resp = readResponse();
        return resp;
    }

    private String readResponse() throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = cin.read();
            if (c < 0) break;
            head.write(c);
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = (c == '\r') ? 1 : 0;
        }
        String h = new String(head.toByteArray(), ASCII);
        String clh = header(h, "Content-Length");
        if (clh != null) {
            int cl = 0;
            try { cl = Integer.parseInt(clh.substring(clh.indexOf(':') + 1).trim()); } catch (Throwable ignored) {}
            if (cl > 0) {
                byte[] body = new byte[cl];
                int got = 0;
                while (got < cl) {
                    int r = cin.read(body, got, cl - got);
                    if (r < 0) break;
                    got += r;
                }
                h += new String(body, 0, got, ASCII);
            }
        }
        return h;
    }

    private static String header(String resp, String name) {
        for (String line : resp.split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) return line;
        }
        return null;
    }

    private static String headerValue(String resp, String name) {
        String l = header(resp, name);
        if (l == null) return null;
        String v = l.substring(l.indexOf(':') + 1).trim();
        int semi = v.indexOf(';');
        return semi >= 0 ? v.substring(0, semi).trim() : v;
    }

    /** 从 SDP / 响应头里取 payload type、内容基地址、轨道控制名、sprop 参数集。 */
    private void parseSdp(String resp) {
        String cb = headerValue(resp, "Content-Base");
        if (cb != null) contentBase = cb;

        boolean inMedia = false;
        for (String line : resp.split("\r\n")) {
            try {
                if (line.startsWith("m=video")) {
                    inMedia = true;
                    String[] p = line.trim().split("\\s+");
                    if (p.length >= 4) rtpPayloadType = Integer.parseInt(p[3]);
                } else if (line.startsWith("a=control:")) {
                    String c = line.substring("a=control:".length()).trim();
                    if (inMedia && !"*".equals(c)) {
                        trackControl = c;          // 媒体段的 control 才是轨道地址
                    }
                } else if (line.startsWith("a=fmtp:")) {
                    for (String kv : line.substring(line.indexOf(':') + 1).split(";")) {
                        kv = kv.trim();
                        int eq = kv.indexOf('=');
                        if (eq < 0) continue;
                        String k = kv.substring(0, eq).trim();
                        String v = kv.substring(eq + 1).trim();
                        if (k.endsWith("sprop-vps")) vps = hexToBytes(v);
                        else if (k.endsWith("sprop-sps")) sps = hexToBytes(v);
                        else if (k.endsWith("sprop-pps")) pps = hexToBytes(v);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        Log.i(TAG, "payloadType=" + rtpPayloadType + "  contentBase=" + contentBase
                + "  track=" + trackControl
                + "  sprop(vps/sps/pps)=" + (vps != null) + "/" + (sps != null) + "/" + (pps != null));
    }

    /** 拼出 SETUP 用的轨道地址（相对 Content-Base 或绝对）。 */
    private String trackUrl(String base) {
        String c = trackControl;
        if (c == null || c.isEmpty()) c = "track1";        // 兜底
        if (c.startsWith("rtsp://")) return c;
        String b = contentBase;
        if (b == null || b.isEmpty()) b = base.endsWith("/") ? base : base + "/";
        if (!b.endsWith("/")) b = b + "/";
        return b + c;
    }

    private static byte[] hexToBytes(String s) {
        s = s.replaceAll("[^0-9a-fA-F]", "");
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    // ---------------------------------------------------------------- RTP 收流 + 拆包

    /**
     * 当前访问单元（AU）：**直接攒进可复用缓冲**，不再每个 NAL 都 new byte[]。
     * 1080p30 的流每秒有几百个 NAL，原来的写法每秒制造几百次分配，
     * 白白喂给 GC（实测 HeapTaskDaemon 占 3.9% 单核）。
     */
    private byte[] auBuf = new byte[64 * 1024];
    private int auLen = 0;
    /** FU 分片重组缓冲：同样可复用。 */
    private byte[] fuBuf = new byte[16 * 1024];
    private int fuLen = 0;
    /** 当前 RTP 时间戳（用来判断一帧结束）。 */
    private long curRtpTs;
    /** 调试：打印前几个 NAL 类型。 */
    private int nalLog;
    /** 调试：打印前几个 RTP 包。 */
    private int dbg;

    private void rtpLoop() {
        byte[] buf = new byte[65535];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                rtpSock.receive(p);
                lastRecvAt = System.currentTimeMillis();
                onRtp(p.getData(), p.getLength());
            } catch (java.net.SocketTimeoutException e) {
                // 正常
            } catch (Throwable t) {
                if (running) Log.w(TAG, "rtpLoop: " + t);
                if (!running) return;
            }
        }
    }

    private void onRtp(byte[] data, int len) {
        if (dbg < 8) {
            dbg++;
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < Math.min(len, 20); i++) h.append(String.format("%02X ", data[i]));
            System.out.println("[pkt] len=" + len + " cc=" + (data[0] & 0x0F)
                    + " v=" + ((data[0] >> 6) & 3) + " pt=" + (data[1] & 0x7F)
                    + " mark=" + ((data[1] & 0x80) != 0) + "  " + h);
        }
        if (len < 12) return;
        int cc = data[0] & 0x0F;
        int headerLen = 12 + cc * 4;
        boolean marker = (data[1] & 0x80) != 0;
        long rtpTs = ((long) (data[4] & 0xff) << 24) | ((data[5] & 0xff) << 16)
                | ((data[6] & 0xff) << 8) | (data[7] & 0xff);
        if (len <= headerLen + 2) return;

        int off = headerLen;
        int nalType = (data[off] >> 1) & 0x3F;
        if (dbg < 12) {
            dbg++;
            Log.i(TAG, "pkt len=" + len + " cc=" + cc + " marker=" + marker
                    + " ts=" + rtpTs + " pt=" + (data[1] & 0x7F) + " nalType=" + nalType
                    + " b0=0x" + Integer.toHexString(data[off] & 0xff));
        }

        if (nalType == NAL_AP) {
            // 聚合包：2 字节 payload header，然后重复 [2字节长度][NAL]
            int p = off + 2;
            while (p + 2 <= len) {
                int sz = ((data[p] & 0xff) << 8) | (data[p + 1] & 0xff);
                p += 2;
                if (sz <= 0 || p + sz > len) break;
                handleNal(data, p, sz);
                p += sz;
            }
        } else if (nalType == NAL_FU) {
            // 分片：2 字节 payload header + 1 字节 FU header
            if (len < off + 3) return;
            int fuHeader = data[off + 2] & 0xFF;
            boolean startBit = (fuHeader & 0x80) != 0;
            boolean endBit = (fuHeader & 0x40) != 0;
            int origType = fuHeader & 0x3F;
            if (startBit) {
                fuLen = 0;
                putFu((byte) ((data[off] & 0x81) | (origType << 1)));
                putFu(data[off + 1]);
            }
            if (fuLen > 0) {
                appendFu(data, off + 3, len - (off + 3));
                if (endBit) {
                    int n = fuLen;
                    byte[] fb = fuBuf;
                    fuLen = 0;
                    handleNal(fb, 0, n);
                }
            }
        } else if (nalType == NAL_PACI) {
            // 不处理
        } else {
            // 单个 NAL 单元
            handleNal(data, off, len - off);
        }

        if (marker) submitAccessUnit();
    }

    /** 收到一个完整 NAL：参数集单独存，其他的攒进当前 AU（全部复用缓冲，零分配）。 */
    private void handleNal(byte[] b, int off, int len) {
        if (len < 2) return;
        int type = (b[off] >> 1) & 0x3F;

        if (type == 32) { vps = copyOf(b, off, len); return; }        // VPS
        if (type == 33) { sps = copyOf(b, off, len); return; }        // SPS
        if (type == 34) { pps = copyOf(b, off, len); return; }        // PPS

        // 这个流既没有 RTP marker、时间戳也恒为 0，只能靠 H.265 自己的
        // first_slice_segment_in_pic_flag（slice header 第一个 bit）判断新一帧开始。
        if (type <= 31 && len > 2) {
            boolean firstSlice = (b[off + 2] & 0x80) != 0;
            if (firstSlice && auLen > 0) submitAccessUnit();
        }
        appendNal(b, off, len);
    }

    /** 写 00 00 00 01 + NAL 数据进 AU 缓冲（不够就翻倍扩容，之后不再分配）。 */
    private void appendNal(byte[] b, int off, int len) {
        int need = auLen + 4 + len;
        if (need > auBuf.length) {
            int cap = auBuf.length;
            while (cap < need) { cap <<= 1; }
            byte[] nb = new byte[cap];
            System.arraycopy(auBuf, 0, nb, 0, auLen);
            auBuf = nb;
        }
        auBuf[auLen++] = 0; auBuf[auLen++] = 0; auBuf[auLen++] = 0; auBuf[auLen++] = 1;
        System.arraycopy(b, off, auBuf, auLen, len);
        auLen += len;
    }

    private void putFu(byte v) {
        if (fuLen >= fuBuf.length) {
            byte[] nb = new byte[fuBuf.length << 1];
            System.arraycopy(fuBuf, 0, nb, 0, fuLen);
            fuBuf = nb;
        }
        fuBuf[fuLen++] = v;
    }

    private void appendFu(byte[] b, int off, int len) {
        int need = fuLen + len;
        if (need > fuBuf.length) {
            int cap = fuBuf.length;
            while (cap < need) { cap <<= 1; }
            byte[] nb = new byte[cap];
            System.arraycopy(fuBuf, 0, nb, 0, fuLen);
            fuBuf = nb;
        }
        System.arraycopy(b, off, fuBuf, fuLen, len);
        fuLen += len;
    }

    private static byte[] copyOf(byte[] b, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(b, off, r, 0, len);
        return r;
    }

    /** 一个 AU 收齐 → 交给解码器。 */
    private void submitAccessUnit() {
        if (auLen == 0) return;
        try {
            ensureCodec();
            if (codec == null) { auLen = 0; return; }

            int idx = codec.dequeueInputBuffer(10000);
            if (idx < 0) { auLen = 0; return; }
            ByteBuffer in = codec.getInputBuffer(idx);
            if (in == null) { auLen = 0; return; }
            in.clear();
            in.put(auBuf, 0, auLen);
            int size = auLen;
            in.flip();
            // 流里没有可用时间戳，自己按 30fps 递增
            long ptsUs = ptsCounter++ * 33333L;
            codec.queueInputBuffer(idx, 0, size, ptsUs, 0);
            auLen = 0;
            frameCount++;

            drain();
        } catch (Throwable t) {
            Log.w(TAG, "submit: " + t);
            auLen = 0;
        }
    }

    private long ptsCounter = 1;

    private void drain() {
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int idx = codec.dequeueOutputBuffer(info, 0);
                if (idx < 0) return;
                codec.releaseOutputBuffer(idx, renderOutput);
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureCodec() {
        if (codec != null) return;
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat("video/hevc", width, height);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            // 低功耗提示：这是侧摄像头（实时预览），告诉解码器别用高性能档。
            // KEY_PRIORITY 0 = 实时；KEY_OPERATING_RATE 给真实帧率（30），
            // 有些解码器（这台的 Hantro）据此选更省电的工作点。
            try {
                fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);
                fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, 30);
            } catch (Throwable ignored) {
            }
            if (vps != null && sps != null && pps != null) {
                ByteArrayOutputStream csd = new ByteArrayOutputStream();
                csd.write(new byte[]{0, 0, 0, 1}); csd.write(vps);
                csd.write(new byte[]{0, 0, 0, 1}); csd.write(sps);
                csd.write(new byte[]{0, 0, 0, 1}); csd.write(pps);
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));
            }
            codec = MediaCodec.createDecoderByType("video/hevc");
            codec.configure(fmt, surface, null, 0);
            codec.start();
            Log.i(TAG, "MediaCodec video/hevc 已启动");
        } catch (Throwable t) {
            Log.e(TAG, "ensureCodec 失败: " + t);
            codec = null;
        }
    }

    private void releaseCodec() {
        if (codec != null) {
            try { codec.stop(); } catch (Throwable ignored) {}
            try { codec.release(); } catch (Throwable ignored) {}
            codec = null;
        }
    }
}
