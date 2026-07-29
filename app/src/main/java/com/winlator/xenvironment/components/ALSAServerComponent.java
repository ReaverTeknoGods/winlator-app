package com.winlator.xenvironment.components;

import com.winlator.alsaserver.ALSAClient;
import com.winlator.alsaserver.ALSAClientConnectionHandler;
import com.winlator.alsaserver.ALSARequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.EnvironmentComponent;

public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;
    private final ALSAClient.Options options;
    private final boolean multithreadedClients;

    public ALSAServerComponent(UnixSocketConfig socketConfig, ALSAClient.Options options) {
        this(socketConfig, options, true);
    }

    public ALSAServerComponent(UnixSocketConfig socketConfig, ALSAClient.Options options,
                               boolean multithreadedClients) {
        this.socketConfig = socketConfig;
        this.options = options;
        this.multithreadedClients = multithreadedClients;
    }

    @Override
    public void start() {
        if (connector != null) return;
        ALSAClient.assignFramesPerBuffer(environment.getContext());
        connector = new XConnectorEpoll(socketConfig, new ALSAClientConnectionHandler(options), new ALSARequestHandler());
        connector.setMultithreadedClients(multithreadedClients);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }
}
