package com.teknoparrot.bridge.v1;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable client request. The raw token is never returned or logged. */
public final class SessionSpec implements Parcelable {
    public static final Creator<SessionSpec> CREATOR = new Creator<SessionSpec>() {
        @Override
        public SessionSpec createFromParcel(Parcel source) {
            return new SessionSpec(source);
        }

        @Override
        public SessionSpec[] newArray(int size) {
            return new SessionSpec[size];
        }
    };

    private final int protocolVersion;
    private final String clientName;
    private final String requestedSessionId;
    private final String tokenHex;
    private final int containerId;
    private final int pipePort;
    private final String pipeName64;
    private final String pipeName32;
    private final int sharedPageBytes;
    private final int flags;

    public SessionSpec(
        int protocolVersion,
        String clientName,
        String requestedSessionId,
        String tokenHex,
        int containerId,
        int pipePort,
        String pipeName64,
        String pipeName32,
        int sharedPageBytes,
        int flags) {
        this.protocolVersion = protocolVersion;
        this.clientName = clientName;
        this.requestedSessionId = requestedSessionId;
        this.tokenHex = tokenHex;
        this.containerId = containerId;
        this.pipePort = pipePort;
        this.pipeName64 = pipeName64;
        this.pipeName32 = pipeName32;
        this.sharedPageBytes = sharedPageBytes;
        this.flags = flags;
    }

    private SessionSpec(Parcel source) {
        protocolVersion = source.readInt();
        clientName = source.readString();
        requestedSessionId = source.readString();
        tokenHex = source.readString();
        containerId = source.readInt();
        pipePort = source.readInt();
        pipeName64 = source.readString();
        pipeName32 = source.readString();
        sharedPageBytes = source.readInt();
        flags = source.readInt();
    }

    public int getProtocolVersion() { return protocolVersion; }
    public String getClientName() { return clientName; }
    public String getRequestedSessionId() { return requestedSessionId; }
    public String getTokenHex() { return tokenHex; }
    public int getContainerId() { return containerId; }
    public int getPipePort() { return pipePort; }
    public String getPipeName64() { return pipeName64; }
    public String getPipeName32() { return pipeName32; }
    public int getSharedPageBytes() { return sharedPageBytes; }
    public int getFlags() { return flags; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel destination, int parcelFlags) {
        destination.writeInt(protocolVersion);
        destination.writeString(clientName);
        destination.writeString(requestedSessionId);
        destination.writeString(tokenHex);
        destination.writeInt(containerId);
        destination.writeInt(pipePort);
        destination.writeString(pipeName64);
        destination.writeString(pipeName32);
        destination.writeInt(sharedPageBytes);
        destination.writeInt(flags);
    }
}
