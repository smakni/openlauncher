package com.syu.ipc;

// Implemented here and handed to the service, which calls back on every change
// to a registered id. Signature and transaction code read from the vendor APK:
// getting either wrong makes the callback silently never fire.
interface IModuleCallback {
    void update(int id, in int[] ints, in float[] floats, in String[] strings);
}
