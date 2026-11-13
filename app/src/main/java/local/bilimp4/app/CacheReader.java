package local.bilimp4.app;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

final class CacheReader {
    static final String DESCRIPTOR = "local.bilimp4.app.ICacheReader";
    static final int LIST = IBinder.FIRST_CALL_TRANSACTION;
    static final int OPEN = LIST + 1;
    static final int OPEN_DANMAKU = LIST + 2;
    static final int DESTROY = 16777115;

    private final IBinder binder;

    CacheReader(IBinder binder) {
        this.binder = binder;
    }

    boolean alive() {
        return binder.isBinderAlive();
    }

    String listCaches() throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            transact(LIST, request, reply);
            return reply.readString();
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    ParcelFileDescriptor openTrack(String directory, boolean audio) throws RemoteException {
        return open(OPEN, directory, audio);
    }

    ParcelFileDescriptor openDanmaku(String directory, boolean protobuf) throws RemoteException {
        return open(OPEN_DANMAKU, directory, protobuf);
    }

    private ParcelFileDescriptor open(int code, String directory, boolean flag) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeString(directory);
            request.writeInt(flag ? 1 : 0);
            transact(code, request, reply);
            return reply.readTypedObject(ParcelFileDescriptor.CREATOR);
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private void transact(int code, Parcel request, Parcel reply) throws RemoteException {
        if (!binder.transact(code, request, reply, 0)) throw new RemoteException("缓存服务已断开。");
        reply.readException();
    }
}
