package com.syu.ipc

import android.os.Parcel
import android.os.Parcelable

/**
 * Placeholder for the vendor's return type on IRemoteModule.get.
 *
 * Its real field layout is not known, and it does not need to be: get() is
 * declared only so that register and unregister keep their transaction codes of
 * 3 and 4, and it is never called. Reading data goes through the callback
 * instead, which delivers plain arrays.
 *
 * If get() is ever wanted, this has to be replaced with the real layout first —
 * unmarshalling against a wrong one yields silent nonsense rather than an error.
 */
class ModuleObject() : Parcelable {

    constructor(parcel: Parcel) : this() {
        parcel.readInt()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ModuleObject> {
        override fun createFromParcel(parcel: Parcel): ModuleObject = ModuleObject(parcel)
        override fun newArray(size: Int): Array<ModuleObject?> = arrayOfNulls(size)
    }
}
