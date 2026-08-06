package com.syu.ipc;

import com.syu.ipc.IRemoteModule;

// Same reasoning: only getRemoteModule is used, but the rest hold their slots
// so its transaction code stays 1.
interface IRemoteToolkit {
    IRemoteModule getRemoteModule(int module);
    int ismapapplication(int value);
    String procName(int value);
    void sendToSyuServiceAudioInformation(int id, in int[] ints, in float[] floats, in String[] strings);
    void notify(int id, String value);
}
