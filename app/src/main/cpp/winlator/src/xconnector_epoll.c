#include <stdbool.h>
#include <jni.h>
#include <sys/epoll.h>
#include <sys/poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/eventfd.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <malloc.h>
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdatomic.h>

#include "winlator.h"
#include "jni_utils.h"

#define MAX_EVENTS 10

typedef struct JMethods {
    JavaVM* jvm;
    JNIEnv* env;
    jobject obj;
    jmethodID handleConnectionShutdown;
    jmethodID handleNewConnection;
    jmethodID handleExistingConnection;
    jmethodID killAllConnections;
} JMethods;

typedef struct XConnectorEpoll {
    pthread_t epollThread;
    bool running;
    int epollFd;
    int serverFd;
    int shutdownFd;
    bool multithreadedClients;
    pthread_mutex_t clientsMutex;
    pthread_cond_t clientsCondition;
    size_t activeClientThreads;
    JMethods jmethods;
} XConnectorEpoll;

typedef struct ConnectedClient {
    pthread_t pollThread;
    int fd;
    int shutdownFd;
    atomic_bool running;
    XConnectorEpoll* connector;
    void* tag;
    JMethods jmethods;
} ConnectedClient;

static int createServerSocket(const char* sockPath) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un serverAddr = {0};
    serverAddr.sun_family = AF_LOCAL;

    int addrLength = sizeof(sa_family_t) + strlen(sockPath);
    strncpy(serverAddr.sun_path, sockPath, sizeof(serverAddr.sun_path) - 1);

    unlink(serverAddr.sun_path);
    if (bind(fd, (struct sockaddr*) &serverAddr, addrLength) < 0) goto error;
    if (listen(fd, MAX_EVENTS) < 0) goto error;

    return fd;

error:
    CLOSEFD(fd);
    return -1;
}

static void loadJMethods(JMethods* jmethods) {
    JNIEnv* env;
    (*jmethods->jvm)->AttachCurrentThread(jmethods->jvm, &env, NULL);
    jmethods->env = env;

    jclass cls = (*env)->GetObjectClass(env, jmethods->obj);
    jmethods->handleConnectionShutdown = (*env)->GetMethodID(env, cls, "handleConnectionShutdown", "(Ljava/lang/Object;)V");
    jmethods->handleNewConnection = (*env)->GetMethodID(env, cls, "handleNewConnection", "(JI)Ljava/lang/Object;");
    jmethods->handleExistingConnection = (*env)->GetMethodID(env, cls, "handleExistingConnection", "(Ljava/lang/Object;)V");
    jmethods->killAllConnections = (*env)->GetMethodID(env, cls, "killAllConnections", "()V");
}

static bool addFdToEpoll(int epollFd, int fd, void* ptr) {
    struct epoll_event event = {0};
    if (ptr) {
        event.data.ptr = ptr;
    }
    else event.data.fd = fd;
    event.events = EPOLLIN;
    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, &event) < 0) return false;
    return true;
}

static void removeFdFromEpoll(int epollFd, int fd) {
    epoll_ctl(epollFd, EPOLL_CTL_DEL, fd, NULL);
}

static int waitForSocketRead(jint clientFd, jint shutdownFd) {
    struct pollfd pfds[2] = {0};
    pfds[0].fd = clientFd;
    pfds[0].events = POLLIN;

    pfds[1].fd = shutdownFd;
    pfds[1].events = POLLIN;

    int res = poll(pfds, 2, -1);
    if (res < 0 || (pfds[1].revents & POLLIN)) return -1;
    return (pfds[0].revents & POLLIN) ? 1 : 0;
}

static void requestShutdown(int fd) {
    const uint64_t shutdownValue = 1;
    write(fd, &shutdownValue, sizeof(uint64_t));
}

static void XConnectorEpoll_destroy(JNIEnv* env, XConnectorEpoll* connector) {
    if (connector->serverFd > 0) {
        removeFdFromEpoll(connector->epollFd, connector->serverFd);
        CLOSEFD(connector->serverFd);
    }

    if (connector->shutdownFd > 0) {
        removeFdFromEpoll(connector->epollFd, connector->shutdownFd);
        CLOSEFD(connector->shutdownFd);
    }

    if (connector->jmethods.obj) {
        (*env)->DeleteGlobalRef(env, connector->jmethods.obj);
        connector->jmethods.obj = NULL;
    }

    CLOSEFD(connector->epollFd);
    pthread_cond_destroy(&connector->clientsCondition);
    pthread_mutex_destroy(&connector->clientsMutex);
    free(connector);
}

static XConnectorEpoll* XConnectorEpoll_allocate(JNIEnv* env, jobject obj, const char* sockPath) {
    XConnectorEpoll* connector = calloc(1, sizeof(XConnectorEpoll));
    if (!connector) return NULL;

    if (pthread_mutex_init(&connector->clientsMutex, NULL) != 0) {
        free(connector);
        return NULL;
    }
    if (pthread_cond_init(&connector->clientsCondition, NULL) != 0) {
        pthread_mutex_destroy(&connector->clientsMutex);
        free(connector);
        return NULL;
    }

    connector->epollFd = epoll_create(MAX_EVENTS);
    if (connector->epollFd < 0) goto error;

    connector->serverFd = createServerSocket(sockPath);
    if (connector->serverFd < 0) goto error;

    connector->shutdownFd = eventfd(0, EFD_NONBLOCK);
    if (connector->shutdownFd < 0) goto error;

    if (!addFdToEpoll(connector->epollFd, connector->serverFd, &connector->serverFd)) goto error;
    if (!addFdToEpoll(connector->epollFd, connector->shutdownFd, &connector->shutdownFd)) goto error;

    (*env)->GetJavaVM(env, &connector->jmethods.jvm);
    connector->jmethods.obj = (*env)->NewGlobalRef(env, obj);
    return connector;

error:
    XConnectorEpoll_destroy(env, connector);
    return NULL;
}

static void XConnectorEpoll_clientThreadStarted(XConnectorEpoll* connector) {
    pthread_mutex_lock(&connector->clientsMutex);
    connector->activeClientThreads++;
    pthread_mutex_unlock(&connector->clientsMutex);
}

static void XConnectorEpoll_clientThreadStopped(XConnectorEpoll* connector) {
    pthread_mutex_lock(&connector->clientsMutex);
    connector->activeClientThreads--;
    if (connector->activeClientThreads == 0)
        pthread_cond_broadcast(&connector->clientsCondition);
    pthread_mutex_unlock(&connector->clientsMutex);
}

static void XConnectorEpoll_waitForClientThreads(XConnectorEpoll* connector) {
    pthread_mutex_lock(&connector->clientsMutex);
    while (connector->activeClientThreads != 0)
        pthread_cond_wait(&connector->clientsCondition, &connector->clientsMutex);
    pthread_mutex_unlock(&connector->clientsMutex);
}

static void XConnectorEpoll_killConnection(XConnectorEpoll* connector, ConnectedClient* client) {
    JMethods* jmethods = &connector->jmethods;

    if (connector->multithreadedClients) {
        if (atomic_exchange(&client->running, false))
            requestShutdown(client->shutdownFd);
        // Client threads are detached and own their descriptors, callback, and
        // native ConnectedClient allocation. This method only requests exit;
        // XConnectorEpoll_stopEpollThread waits for every detached client before
        // the connector's JNI global reference is released.
        return;
    }

    atomic_store(&client->running, false);
    removeFdFromEpoll(connector->epollFd, client->fd);

    CLOSEFD(client->fd);

    if (client->tag) {
        (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->handleConnectionShutdown, client->tag);
        client->tag = NULL;
    }
    free(client);
}

static void* pollThread(void* param) {
    ConnectedClient* client = param;
    XConnectorEpoll* connector = client->connector;
    loadJMethods(&client->jmethods);
    JMethods* jmethods = &client->jmethods;
    client->tag = (*jmethods->env)->CallObjectMethod(jmethods->env, jmethods->obj, jmethods->handleNewConnection, (jlong)client, client->fd);

    int res;
    do {
        res = waitForSocketRead(client->fd, client->shutdownFd);
        if (res == 1) (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->handleExistingConnection, client->tag);
    }
    while (atomic_load(&client->running) && res >= 0);

    if (client->tag) {
        (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->handleConnectionShutdown, client->tag);
        client->tag = NULL;
    }
    CLOSEFD(client->shutdownFd);
    CLOSEFD(client->fd);
    (*jmethods->jvm)->DetachCurrentThread(jmethods->jvm);
    free(client);
    XConnectorEpoll_clientThreadStopped(connector);
    return NULL;
}

static void XConnectorEpoll_handleNewConnection(XConnectorEpoll* connector, int clientFd) {
    JMethods* jmethods = &connector->jmethods;
    ConnectedClient* client = calloc(1, sizeof(ConnectedClient));
    if (!client) {
        CLOSEFD(clientFd);
        return;
    }

    client->fd = clientFd;
    client->connector = connector;
    atomic_init(&client->running, true);

    if (connector->multithreadedClients) {
        client->jmethods.jvm = connector->jmethods.jvm;
        client->jmethods.obj = connector->jmethods.obj;
        client->shutdownFd = eventfd(0, EFD_NONBLOCK);
        if (client->shutdownFd < 0) {
            CLOSEFD(client->fd);
            free(client);
            return;
        }

        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        XConnectorEpoll_clientThreadStarted(connector);
        int createResult = pthread_create(&client->pollThread, &attr, pollThread, client);
        pthread_attr_destroy(&attr);
        if (createResult != 0) {
            XConnectorEpoll_clientThreadStopped(connector);
            CLOSEFD(client->shutdownFd);
            CLOSEFD(client->fd);
            free(client);
        }
    }
    else {
        if (!addFdToEpoll(connector->epollFd, clientFd, client)) {
            CLOSEFD(client->fd);
            free(client);
            return;
        }
        client->tag = (*jmethods->env)->CallObjectMethod(jmethods->env, jmethods->obj, jmethods->handleNewConnection, (jlong)client, clientFd);
    }
}

static void* epollThread(void* param) {
    XConnectorEpoll* connector = param;
    JMethods* jmethods = &connector->jmethods;
    loadJMethods(jmethods);
    struct epoll_event events[MAX_EVENTS] = {0};

    while (connector->running) {
        int numFds = epoll_wait(connector->epollFd, events, MAX_EVENTS, -1);
        if (numFds < 0) break;
        for (int i = 0; i < numFds; i++) {
            if (events[i].data.ptr == &connector->serverFd) {
                int clientFd = accept(connector->serverFd, NULL, NULL);
                if (clientFd >= 0) XConnectorEpoll_handleNewConnection(connector, clientFd);
            }
            else if (events[i].data.ptr != &connector->shutdownFd &&
                    (events[i].events & EPOLLIN)) {
                ConnectedClient* client = events[i].data.ptr;
                (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->handleExistingConnection, client->tag);
            }
        }
    }

    (*jmethods->env)->CallVoidMethod(jmethods->env, jmethods->obj, jmethods->killAllConnections);
    (*jmethods->jvm)->DetachCurrentThread(jmethods->jvm);
    return NULL;
}

static void XConnectorEpoll_startEpollThread(XConnectorEpoll* connector, bool multithreadedClients) {
    if (connector->running) return;
    connector->running = true;
    connector->multithreadedClients = multithreadedClients;
    pthread_create(&connector->epollThread, NULL, epollThread, connector);
}

static void XConnectorEpoll_stopEpollThread(XConnectorEpoll* connector) {
    if (!connector->running) return;
    connector->running = false;
    requestShutdown(connector->shutdownFd);

    pthread_join(connector->epollThread, NULL);
    connector->epollThread = 0;
    XConnectorEpoll_waitForClientThreads(connector);
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_closeFd(jint fd) {
    CLOSEFD(fd);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_nativeAllocate(JNIEnv* env, jobject obj,
                                                            jstring sockPath) {
    const char* pathPtr = (*env)->GetStringUTFChars(env, sockPath, 0);
    XConnectorEpoll* connector = XConnectorEpoll_allocate(env, obj, pathPtr);

    (*env)->ReleaseStringUTFChars(env, sockPath, pathPtr);
    return (jlong)connector;
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_destroy(JNIEnv *env, jobject obj, jlong nativePtr) {
    XConnectorEpoll_destroy(env, (XConnectorEpoll*)nativePtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_startEpollThread(JNIEnv *env, jobject obj,
                                                              jlong nativePtr, jboolean multithreadedClients) {
    XConnectorEpoll_startEpollThread((XConnectorEpoll*)nativePtr, multithreadedClients);
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_stopEpollThread(JNIEnv *env, jobject obj,
                                                             jlong nativePtr) {
    XConnectorEpoll_stopEpollThread((XConnectorEpoll*)nativePtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_xconnector_XConnectorEpoll_killConnection(JNIEnv *env, jclass obj,
                                                            jlong connectorPtr, jlong clientPtr) {
    XConnectorEpoll_killConnection((XConnectorEpoll*)connectorPtr, (ConnectedClient*)clientPtr);
}
