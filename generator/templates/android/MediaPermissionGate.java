package __PACKAGE__;

import java.util.ArrayList;
import java.util.List;

// Decides web media permission requests (getUserMedia) for the shell, with no Android types so
// that a plain JVM test runs every interleaving (generator/test/MediaPermissionGateTest.java).
//
// Capacitor's BridgeWebChromeClient shares one permission listener between media, geolocation and
// the camera of the file chooser, so the result of the system dialog can be lost, and answering a
// request the WebView already resolved throws. The gate keeps one dialog in progress for all media
// requests and, whatever the dialog reports, decides each queued request once by the permissions
// the app holds.
//
// Lost answer: while the system dialog is up the activity is paused, and its result is delivered
// before the activity resumes. So a request that arrives with a dialog in progress, after the
// activity was paused for it and is resumed again, means the answer was lost for good: the queue is
// settled by the permissions held and a fresh dialog starts. While the activity is not resumed the
// dialog is still up and the request just queues. Main thread only.
public final class MediaPermissionGate<R> {

    public interface Host<R> {
        // Empty when the request is not for capture.
        List<String> needs(R request);
        boolean allDeclared(List<String> permissions);
        boolean allHeld(List<String> permissions);
        boolean fromAppHost(R request);
        void grant(R request);
        void deny(R request);
        void launchDialog(R request);
        boolean isResumed();
    }

    private final Host<R> host;
    private final List<R> pending = new ArrayList<>();
    private boolean inProgress = false;
    private boolean pausedSinceLaunch = false;

    public MediaPermissionGate(Host<R> host) {
        this.host = host;
    }

    // False when the request is not for capture and the bridge should handle it.
    public boolean handle(R request) {
        List<String> needs = host.needs(request);
        if (needs.isEmpty()) return false;
        // A permission the manifest does not declare can never be granted, so there is no dialog.
        if (!host.fromAppHost(request) || !host.allDeclared(needs)) {
            deny(request);
            return true;
        }
        if (inProgress && pausedSinceLaunch && host.isResumed()) settle();
        if (host.allHeld(needs)) {
            grant(request);
            return true;
        }
        pending.add(request);
        if (!inProgress) {
            inProgress = true;
            pausedSinceLaunch = false;
            host.launchDialog(request);
        }
        return true;
    }

    // The reported boolean is not used: the permissions held decide.
    public void onDialogResult() {
        if (inProgress) settle();
    }

    public void onActivityPaused() {
        if (inProgress) pausedSinceLaunch = true;
    }

    // Answering a request the WebView cancelled would throw.
    public void cancel(R request) {
        pending.remove(request);
    }

    private void settle() {
        List<R> queued = new ArrayList<>(pending);
        pending.clear();
        inProgress = false;
        for (R request : queued) {
            if (host.allHeld(host.needs(request))) grant(request); else deny(request);
        }
    }

    // The WebView may have resolved the request already, and answering it throws.
    private void grant(R request) {
        try { host.grant(request); } catch (IllegalStateException ignored) { }
    }

    private void deny(R request) {
        try { host.deny(request); } catch (IllegalStateException ignored) { }
    }
}
