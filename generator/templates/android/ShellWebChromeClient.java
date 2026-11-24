package __PACKAGE__;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Message;
import android.webkit.PermissionRequest;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// The Android side of MediaPermissionGate (see its comment for why the shell replaces the bridge's
// chrome client). Besides media it forwards window.open to ShellPopups; everything else stays the bridge's.
public class ShellWebChromeClient extends BridgeWebChromeClient {

    // Only the page of the app itself may capture; frames from other hosts are denied.
    private static final String APP_HOST = "__APP_HOST__";

    private final Bridge shellBridge;
    private final MediaPermissionGate<PermissionRequest> gate;
    private final ShellPopups popups;

    public ShellWebChromeClient(Bridge bridge, ShellPopups popups) {
        super(bridge);
        this.shellBridge = bridge;
        this.popups = popups;
        this.gate = new MediaPermissionGate<>(new MediaPermissionGate.Host<PermissionRequest>() {
            @Override public List<String> needs(PermissionRequest request) { return needsOf(request); }
            @Override public boolean allDeclared(List<String> permissions) { return declared(permissions); }
            @Override public boolean allHeld(List<String> permissions) { return held(permissions); }

            @Override public boolean fromAppHost(PermissionRequest request) {
                Uri origin = request.getOrigin();
                return origin != null && origin.getHost() != null && APP_HOST.equalsIgnoreCase(origin.getHost());
            }

            @Override public void grant(PermissionRequest request) { request.grant(request.getResources()); }
            @Override public void deny(PermissionRequest request) { request.deny(); }

            @Override public void launchDialog(final PermissionRequest first) {
                ShellWebChromeClient.super.onPermissionRequest(new PermissionRequest() {
                    private boolean answered = false;

                    @Override public Uri getOrigin() { return first.getOrigin(); }
                    @Override public String[] getResources() { return first.getResources(); }
                    @Override public void grant(String[] ignored) { answer(); }
                    @Override public void deny() { answer(); }

                    private void answer() {
                        if (answered) return;
                        answered = true;
                        gate.onDialogResult();
                    }
                });
            }

            @Override public boolean isResumed() {
                return shellBridge.getActivity().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
            }
        });
        bridge.getActivity().getLifecycle().addObserver((androidx.lifecycle.LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_PAUSE) gate.onActivityPaused();
        });
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        if (!gate.handle(request)) super.onPermissionRequest(request);
    }

    // The bridge leaves multiple windows off; the shell turns them on for the popup contract (see ShellPopups).
    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        return popups.onCreateWindow(view, isUserGesture, resultMsg);
    }

    @Override
    public void onPermissionRequestCanceled(PermissionRequest request) {
        gate.cancel(request);
        super.onPermissionRequestCanceled(request);
    }

    private List<String> needsOf(PermissionRequest request) {
        List<String> resources = Arrays.asList(request.getResources());
        List<String> needs = new ArrayList<>();
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needs.add(Manifest.permission.CAMERA);
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            needs.add(Manifest.permission.RECORD_AUDIO);
            needs.add(Manifest.permission.MODIFY_AUDIO_SETTINGS);
        }
        return needs;
    }

    private boolean held(List<String> permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(shellBridge.getContext(), permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean declared(List<String> permissions) {
        try {
            PackageInfo info = shellBridge.getContext().getPackageManager()
                .getPackageInfo(shellBridge.getContext().getPackageName(), PackageManager.GET_PERMISSIONS);
            List<String> requested = info.requestedPermissions == null ? new ArrayList<String>() : Arrays.asList(info.requestedPermissions);
            return requested.containsAll(permissions);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
