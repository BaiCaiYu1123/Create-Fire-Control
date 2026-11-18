package com.hooya.stabilizedturret.client.preview;

/** Allocation-free inactive guard; ordinary rendering does not initialize preview geometry or thread locals. */
public final class PreviewCaptureScope {
    private static volatile Thread thread;
    public static boolean active(){Thread current=thread;return current!=null&&current==Thread.currentThread();}
    static Thread enter(){Thread previous=thread;thread=Thread.currentThread();return previous;}
    static void exit(Thread previous){thread=previous;}
    private PreviewCaptureScope() {}
}
