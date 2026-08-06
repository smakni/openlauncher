package com.syu.ipc;

import com.syu.ipc.IModuleCallback;
import com.syu.ipc.ModuleObject;

// Declaration order is what assigns transaction codes, so every method is
// listed in the order the vendor stub reports — cmd 1, get 2, register 3,
// unregister 4 — even though only register and unregister are called here.
interface IRemoteModule {
    void cmd(int id, in int[] ints, in float[] floats, in String[] strings);
    ModuleObject get(int id, in int[] ints, in float[] floats, in String[] strings);
    void register(IModuleCallback callback, int id, int flag);
    void unregister(IModuleCallback callback, int id);
}
