package com.teknoparrot.bridge.v1;

import android.os.Parcel;
import android.os.Parcelable;

/** Versioned, immutable capability snapshot returned by the companion. */
public final class BridgeCapabilities implements Parcelable {
    public static final Creator<BridgeCapabilities> CREATOR = new Creator<BridgeCapabilities>() {
        @Override
        public BridgeCapabilities createFromParcel(Parcel source) {
            return new BridgeCapabilities(source);
        }

        @Override
        public BridgeCapabilities[] newArray(int size) {
            return new BridgeCapabilities[size];
        }
    };

    private final int protocolVersion;
    private final int minimumProtocolVersion;
    private final int featureFlags;
    private final int maximumSharedPageBytes;
    private final int maximumPipeNameBytes;
    private final String implementation;

    public BridgeCapabilities(
        int protocolVersion,
        int minimumProtocolVersion,
        int featureFlags,
        int maximumSharedPageBytes,
        int maximumPipeNameBytes,
        String implementation) {
        this.protocolVersion = protocolVersion;
        this.minimumProtocolVersion = minimumProtocolVersion;
        this.featureFlags = featureFlags;
        this.maximumSharedPageBytes = maximumSharedPageBytes;
        this.maximumPipeNameBytes = maximumPipeNameBytes;
        this.implementation = implementation != null ? implementation : "";
    }

    private BridgeCapabilities(Parcel source) {
        protocolVersion = source.readInt();
        minimumProtocolVersion = source.readInt();
        featureFlags = source.readInt();
        maximumSharedPageBytes = source.readInt();
        maximumPipeNameBytes = source.readInt();
        implementation = source.readString();
    }

    public int getProtocolVersion() { return protocolVersion; }
    public int getMinimumProtocolVersion() { return minimumProtocolVersion; }
    public int getFeatureFlags() { return featureFlags; }
    public int getMaximumSharedPageBytes() { return maximumSharedPageBytes; }
    public int getMaximumPipeNameBytes() { return maximumPipeNameBytes; }
    public String getImplementation() { return implementation; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeInt(protocolVersion);
        destination.writeInt(minimumProtocolVersion);
        destination.writeInt(featureFlags);
        destination.writeInt(maximumSharedPageBytes);
        destination.writeInt(maximumPipeNameBytes);
        destination.writeString(implementation);
    }
}
