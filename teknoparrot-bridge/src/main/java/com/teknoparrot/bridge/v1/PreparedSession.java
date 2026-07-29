package com.teknoparrot.bridge.v1;

import android.os.Parcel;
import android.os.Parcelable;

/** Sanitized effective settings returned after companion validation. */
public final class PreparedSession implements Parcelable {
    public static final Creator<PreparedSession> CREATOR = new Creator<PreparedSession>() {
        @Override
        public PreparedSession createFromParcel(Parcel source) {
            return new PreparedSession(source);
        }

        @Override
        public PreparedSession[] newArray(int size) {
            return new PreparedSession[size];
        }
    };

    private final int protocolVersion;
    private final String sessionId;
    private final int containerId;
    private final int pipePort;
    private final String pipeName64;
    private final String pipeName32;
    private final int sharedPageBytes;
    private final int featureFlags;
    private final String state;

    public PreparedSession(
        int protocolVersion,
        String sessionId,
        int containerId,
        int pipePort,
        String pipeName64,
        String pipeName32,
        int sharedPageBytes,
        int featureFlags,
        String state) {
        this.protocolVersion = protocolVersion;
        this.sessionId = sessionId;
        this.containerId = containerId;
        this.pipePort = pipePort;
        this.pipeName64 = pipeName64;
        this.pipeName32 = pipeName32;
        this.sharedPageBytes = sharedPageBytes;
        this.featureFlags = featureFlags;
        this.state = state != null ? state : "";
    }

    private PreparedSession(Parcel source) {
        protocolVersion = source.readInt();
        sessionId = source.readString();
        containerId = source.readInt();
        pipePort = source.readInt();
        pipeName64 = source.readString();
        pipeName32 = source.readString();
        sharedPageBytes = source.readInt();
        featureFlags = source.readInt();
        state = source.readString();
    }

    public int getProtocolVersion() { return protocolVersion; }
    public String getSessionId() { return sessionId; }
    public int getContainerId() { return containerId; }
    public int getPipePort() { return pipePort; }
    public String getPipeName64() { return pipeName64; }
    public String getPipeName32() { return pipeName32; }
    public int getSharedPageBytes() { return sharedPageBytes; }
    public int getFeatureFlags() { return featureFlags; }
    public String getState() { return state; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(protocolVersion);
        destination.writeString(sessionId);
        destination.writeInt(containerId);
        destination.writeInt(pipePort);
        destination.writeString(pipeName64);
        destination.writeString(pipeName32);
        destination.writeInt(sharedPageBytes);
        destination.writeInt(featureFlags);
        destination.writeString(state);
    }
}
