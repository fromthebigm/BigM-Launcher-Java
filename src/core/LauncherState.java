package core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The window's observable state: per-app update status + the launcher-level
 * download banner + the self-update offer state. All writes go through
 * services on any thread; observers are invoked on that same thread (the UI
 * marshals to SWT's dispatch itself).
 */
public final class LauncherState {

    public record AppStatus(Phase phase, String message) {
        public enum Phase { IDLE, CHECKING, UPDATE_AVAILABLE, DOWNLOADING, INSTALLING, UP_TO_DATE, FAILED, SELF_MANAGED }
    }

    public interface Listener {
        void appStatus(String appId, AppStatus status);
        void downloadMessage(String message);
        void selfState(boolean available, String tag, String message);
    }

    private final Map<String, AppStatus> appStates = new ConcurrentHashMap<>();
    private final java.util.List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile String downloadMessage;
    private volatile boolean selfAvailable;
    private volatile String selfTag = "";
    private volatile String selfMessage = "";

    public void add(Listener listener) { listeners.add(listener); }
    public void remove(Listener listener) { listeners.remove(listener); }

    public AppStatus app(String appId) {
        return appStates.getOrDefault(appId, new AppStatus(AppStatus.Phase.IDLE, ""));
    }

    public void setApp(String appId, AppStatus status) {
        appStates.put(appId, status);
        for (Listener listener : listeners) listener.appStatus(appId, status);
    }

    public String downloadMessage() { return downloadMessage; }
    public void setDownloadMessage(String message) {
        downloadMessage = message;
        for (Listener listener : listeners) listener.downloadMessage(message);
    }

    public boolean selfAvailable() { return selfAvailable; }
    public String selfTag() { return selfTag; }
    public String selfMessage() { return selfMessage; }
    public void setSelf(boolean available, String tag, String message) {
        selfAvailable = available;
        selfTag = tag;
        selfMessage = message;
        for (Listener listener : listeners) listener.selfState(available, tag, message);
    }
}
